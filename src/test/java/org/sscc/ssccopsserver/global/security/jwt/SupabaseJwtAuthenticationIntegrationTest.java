package org.sscc.ssccopsserver.global.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;

/*
 * 실제 JWKS 없이도 필터체인 전체(디코딩 -> SupabaseJwtAuthenticationConverter -> MemberService)를
 * 검증하기 위해 JwtDecoder만 고정된 Jwt를 반환하도록 대체한다.
 * SecurityMockMvcRequestPostProcessors.jwt()는 커스텀 컨버터를 우회하므로 여기서는 쓰지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(SupabaseJwtAuthenticationIntegrationTest.StubJwtDecoderConfig.class)
@Transactional
class SupabaseJwtAuthenticationIntegrationTest {

    private static final UUID SPB_USER_ID = UUID.randomUUID();

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;

    @Test
    void requestWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/examples/1")).andExpect(status().isUnauthorized());
    }

    @Test
    void requestWithValidTokenProvisionsTemporaryMemberAndPassesAuthentication() throws Exception {
        // 존재하지 않는 예시라 404지만, 401이 아니라는 것 자체가 인증이 통과했다는 뜻
        mockMvc.perform(get("/examples/1").header("Authorization", "Bearer any-token"))
                .andExpect(status().isNotFound());

        assertThat(memberRepository.findBySpbUserId(SPB_USER_ID)).isPresent();
    }

    @TestConfiguration
    static class StubJwtDecoderConfig {

        @Bean
        @Primary
        JwtDecoder jwtDecoder() {
            return token ->
                    Jwt.withTokenValue(token)
                            .header("alg", "none")
                            .subject(SPB_USER_ID.toString())
                            .claim("email", "test@sscc.org")
                            .issuedAt(Instant.now())
                            .expiresAt(Instant.now().plusSeconds(60))
                            .build();
        }
    }
}
