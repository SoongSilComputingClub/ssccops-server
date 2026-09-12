package org.sscc.ssccopsserver.global.security.jwt;

import java.util.List;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;

/*
 * Supabase JWT에 붙이는 클레임 검증기 — issuer·audience (#383 · ssccops#313 · ADR-0026).
 *
 * `NimbusJwtDecoder.withJwkSetUri(...)`의 기본 검증기는 timestamp(exp·nbf)뿐이라 iss·aud를 보지
 * 않았다. 서명 키가 프로젝트 전용이라 지금까지는 위험이 낮았지만, MCP를 위해 Supabase OAuth 2.1
 * 서버를 켜면 **같은 키로 서명된 토큰이 client_id를 달고 여러 클라이언트에게 발급된다** —
 * 어느 발급자의, 누구를 향한 토큰인지를 서버가 직접 보는 것이 그때부터의 최소 조건이다.
 *
 * SecurityConfig 안에 두지 않고 따로 뺀 것은 **단위 테스트가 같은 검증기를 실제 디코더에
 * 끼워 볼 수 있게 하기 위해서**다(로컬 EC 키로 서명한 토큰 · JWKS 네트워크 없음). 통합
 * 테스트의 스텁 `jwtDecoder`(ADR-0009)는 이 검증기를 거치지 않는다 — 토큰 문자열이 곧 subject인
 * 계약이라 iss·aud가 없고, 거기서 막으면 12종 테스트가 전부 죽는다.
 */
public final class SupabaseJwtValidators {

    /*
     * Supabase가 사용자 토큰에 넣는 audience. anon·service_role 키는 aud가 다르거나 없고 HS256
     * 이라 알고리즘 목록에서 이미 떨어지지만, 그것에만 기대지 않고 여기서도 본다.
     */
    static final String REQUIRED_AUDIENCE = "authenticated";

    private SupabaseJwtValidators() {}

    /**
     * 기본 검증(exp·nbf) + issuer 일치 + audience에 {@value #REQUIRED_AUDIENCE} 포함.
     *
     * @param issuer 프로필의 Supabase issuer — {@code {SUPABASE_URL}/auth/v1}
     */
    public static OAuth2TokenValidator<Jwt> create(String issuer) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), new AudienceValidator());
    }

    /*
     * `JwtClaimValidator<List<String>>`로 쓰지 않은 이유 — aud 클레임은 단일 문자열로도 올 수
     * 있고 Nimbus가 그것을 List로 정규화하긴 하지만, `Jwt.getAudience()`가 그 정규화를 이미
     * 담고 있어 타입 캐스팅을 다시 쓸 이유가 없다.
     */
    private static final class AudienceValidator implements OAuth2TokenValidator<Jwt> {

        private static final OAuth2Error MISSING_AUDIENCE =
                new OAuth2Error(
                        OAuth2ErrorCodes.INVALID_TOKEN,
                        "aud 클레임에 '" + REQUIRED_AUDIENCE + "'가 없습니다.",
                        null);

        @Override
        public OAuth2TokenValidatorResult validate(Jwt token) {
            List<String> audience = token.getAudience();
            if (audience != null && audience.contains(REQUIRED_AUDIENCE)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(MISSING_AUDIENCE);
        }
    }
}
