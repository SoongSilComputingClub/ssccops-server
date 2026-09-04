package org.sscc.ssccopsserver.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class TestJwtDecoderConfigTest {

    private final TestJwtDecoderConfig config = new TestJwtDecoderConfig();

    @Test
    void bearerTokenBecomesSubjectWithCommonSupabaseClaims() {
        Jwt jwt = config.jwtDecoder().decode("43d56aa7-d5e7-4a5a-8a7e-3f5d71b5022f");

        assertThat(jwt.getSubject()).isEqualTo("43d56aa7-d5e7-4a5a-8a7e-3f5d71b5022f");
        assertThat(jwt.getClaimAsString("email"))
                .isEqualTo("43d56aa7-d5e7-4a5a-8a7e-3f5d71b5022f@sscc.org");
        assertThat(jwt.getClaimAsMap("user_metadata")).isEqualTo(Map.of("full_name", "테스트"));
        assertThat(jwt.getClaimAsMap("app_metadata")).isEqualTo(Map.of("provider", "google"));
    }

    @Test
    void malformedSubjectIsKeptForAuthenticationFailureTests() {
        Jwt jwt = config.jwtDecoder().decode("not-a-uuid");

        assertThat(jwt.getSubject()).isEqualTo("not-a-uuid");
    }
}
