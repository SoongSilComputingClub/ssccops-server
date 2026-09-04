package org.sscc.ssccopsserver.support;

import java.time.Instant;
import java.util.Map;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * 인증 통합 테스트가 공유하는 JWT 계약(ADR-0009).
 *
 * <p>Bearer 토큰을 그대로 subject로 사용하므로 요청만 읽어도 어떤 회원으로 호출했는지 알 수 있다. 가입 전 세션과 가입 API도 같은 디코더를 쓸 수 있도록
 * Supabase가 제공하는 최소 metadata를 함께 넣는다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestJwtDecoderConfig {

    @Bean
    @Primary
    JwtDecoder jwtDecoder() {
        return token ->
                Jwt.withTokenValue(token)
                        .header("alg", "none")
                        .subject(token)
                        .claim("email", token + "@sscc.org")
                        .claim("user_metadata", Map.of("full_name", "테스트"))
                        .claim("app_metadata", Map.of("provider", "google"))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(60))
                        .build();
    }
}
