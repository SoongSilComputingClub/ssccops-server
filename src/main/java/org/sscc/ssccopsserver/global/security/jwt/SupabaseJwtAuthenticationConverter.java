package org.sscc.ssccopsserver.global.security.jwt;

import java.util.Map;
import java.util.UUID;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.service.MemberService;
import org.sscc.ssccopsserver.global.security.AuthenticatedUser;

import lombok.RequiredArgsConstructor;

/*
 * Supabase Auth가 발급한 JWT를 AuthenticatedUser 주체로 변환한다.
 *
 * 여기서는 회원을 '조회만' 한다. 인증 시점에 회원을 만들어 버리면 "로그인은 했지만 아직 가입하지
 * 않은 사용자"라는 상태가 존재할 수 없고, 조회 요청 하나에도 쓰기 트랜잭션이 열린다.
 * mbr 행은 회원가입 API에서만 생성된다.
 *
 * JWT의 role 클레임은 Postgres RLS용이라 인가 판단에 쓰지 않는다.
 */
@Component
@RequiredArgsConstructor
public class SupabaseJwtAuthenticationConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final String EMAIL_CLAIM = "email";
    private static final String USER_METADATA_CLAIM = "user_metadata";
    private static final String APP_METADATA_CLAIM = "app_metadata";

    private final MemberService memberService;

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UUID authUserId = parseAuthUserId(jwt);
        MemberEntity member = memberService.findByAuthUserId(authUserId).orElse(null);

        AuthenticatedUser principal =
                new AuthenticatedUser(
                        authUserId,
                        jwt.getClaimAsString(EMAIL_CLAIM),
                        displayName(jwt),
                        provider(jwt),
                        member);

        return new SupabaseAuthenticationToken(principal, jwt);
    }

    private UUID parseAuthUserId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException e) {
            throw new InvalidBearerTokenException("sub 클레임이 올바른 UUID가 아닙니다.", e);
        }
    }

    /*
     * 소셜 계정의 표시 이름. 프로바이더마다 넣는 키가 달라(구글은 full_name과 name을 모두 채운다)
     * 우선순위를 두고 찾는다. 가입 화면의 이름 프리필에만 쓰이므로 없으면 null이어도 된다.
     */
    private String displayName(Jwt jwt) {
        return firstStringClaim(jwt, USER_METADATA_CLAIM, "full_name", "name");
    }

    // 소셜 로그인 제공자(google 등). mbr 컬럼이 아니라 인증 정보라서 여기서만 얻을 수 있다.
    private String provider(Jwt jwt) {
        return firstStringClaim(jwt, APP_METADATA_CLAIM, "provider");
    }

    /*
     * 중첩 클레임에서 첫 번째로 채워진 문자열 값을 꺼낸다.
     * getClaimAsMap()은 값이 Map이 아니면 예외를 던지므로, 토큰 구조가 달라져도 인증 자체는
     * 깨지지 않도록 타입을 직접 확인한다.
     */
    private String firstStringClaim(Jwt jwt, String claimName, String... keys) {
        if (!(jwt.getClaim(claimName) instanceof Map<?, ?> claim)) {
            return null;
        }
        for (String key : keys) {
            if (claim.get(key) instanceof String value && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
