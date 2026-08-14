package org.sscc.ssccopsserver.global.security.resolver;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/*
 * 로그인은 했지만 아직 가입하지 않은 사용자가 회원이 필요한 엔드포인트를 호출했을 때의 응답.
 *
 * 토큰이 유효하므로 401이 아니고, 재로그인해도 해결되지 않는 상태라 프론트가 가입 화면으로
 * 보낼 수 있도록 403 SIGNUP_REQUIRED로 끊는다. 이 판정은 @CurrentMember 리졸버 한 곳에서만 한다.
 *
 * 이 테스트는 회원을 만들지 않는다 — StubJwtDecoder가 주는 sub에 연결된 mbr이 없는 상태 자체가 전제다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(CurrentMemberArgumentResolverIntegrationTest.StubJwtDecoderConfig.class)
@Transactional
class CurrentMemberArgumentResolverIntegrationTest {

    private static final UUID AUTH_USER_ID = UUID.randomUUID();

    @Autowired private MockMvc mockMvc;

    @Test
    void unregisteredUserGetsSignupRequired() throws Exception {
        // 본문은 @CurrentMember보다 먼저 바인딩되므로, 400이 아니라 403이 나오도록 형식은 갖춰 보낸다
        String body =
                """
                {
                  "title": "가입 전 사용자의 업무 등록",
                  "itemType": "EVENT",
                  "ownerId": 1
                }
                """;

        mockMvc.perform(
                        post("/v1/works")
                                .header("Authorization", "Bearer any-token")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("SIGNUP_REQUIRED"));
    }

    @TestConfiguration
    static class StubJwtDecoderConfig {

        @Bean
        @Primary
        JwtDecoder jwtDecoder() {
            return token ->
                    Jwt.withTokenValue(token)
                            .header("alg", "none")
                            .subject(AUTH_USER_ID.toString())
                            .claim("email", "newcomer@sscc.org")
                            .issuedAt(Instant.now())
                            .expiresAt(Instant.now().plusSeconds(60))
                            .build();
        }
    }
}
