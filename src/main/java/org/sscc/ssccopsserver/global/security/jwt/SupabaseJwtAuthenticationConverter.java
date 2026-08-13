package org.sscc.ssccopsserver.global.security.jwt;

import java.util.UUID;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.service.MemberService;

import lombok.RequiredArgsConstructor;

/*
 * Supabase Auth가 발급한 JWT의 sub(회원 식별자)/email 클레임으로 MemberEntity를 찾거나
 * 임시회원으로 즉시 프로비저닝한다. JWT의 role 클레임은 Postgres RLS용이라 인가 판단에 쓰지 않는다.
 */
@Component
@RequiredArgsConstructor
public class SupabaseJwtAuthenticationConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    private final MemberService memberService;

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UUID authUserId = parseAuthUserId(jwt);
        String email = jwt.getClaimAsString("email");

        MemberEntity member = memberService.findOrProvisionByAuthUserId(authUserId, email);

        return new SupabaseAuthenticationToken(member, jwt);
    }

    private UUID parseAuthUserId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException e) {
            throw new InvalidBearerTokenException("sub 클레임이 올바른 UUID가 아닙니다.", e);
        }
    }
}
