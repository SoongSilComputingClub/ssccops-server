package org.sscc.ssccopsserver.global.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    private static final UUID AUTH_USER_ID = UUID.randomUUID();

    // 이 토큰 값일 때만 스텁 디코더가 sub를 UUID가 아닌 값으로 내려준다
    private static final String NON_UUID_SUBJECT_TOKEN = "non-uuid-subject";

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;

    // 배포 플랫폼의 헬스 프로브는 토큰을 붙일 수 없으므로 인증 없이 통과해야 한다
    @Test
    void healthEndpointIsOpenWithoutToken() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    // 지표는 계속 보호 대상이다 — 헬스만 열었지 actuator 전체를 연 것이 아니다
    @Test
    void metricsEndpointStillRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
    }

    @Test
    void requestWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/examples/1")).andExpect(status().isUnauthorized());
    }

    /*
     * sub가 UUID가 아니면 우리 회원 식별 체계로 해석할 수 없는 토큰이다.
     * 컨버터가 던지는 InvalidBearerTokenException이 EntryPoint를 타고 ApiResponse 포맷의
     * 401로 나가는지까지 확인한다 — 프론트가 오류 본문을 코드로 분기하기 때문이다.
     */
    @Test
    void tokenWithNonUuidSubjectReturns401InApiResponseFormat() throws Exception {
        mockMvc.perform(
                        get("/examples/1")
                                .header("Authorization", "Bearer " + NON_UUID_SUBJECT_TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON401"));
    }

    /*
     * 가입하지 않은 사용자도 토큰이 유효하면 인증은 통과한다. 존재하지 않는 예시라 404지만,
     * 401이 아니라는 것 자체가 인증이 통과했다는 뜻이다.
     * 동시에 인증만으로는 회원이 만들어지지 않아야 한다 — mbr 행 생성은 가입 API의 책임이다.
     */
    @Test
    void validTokenAuthenticatesWithoutCreatingMember() throws Exception {
        mockMvc.perform(get("/examples/1").header("Authorization", "Bearer any-token"))
                .andExpect(status().isNotFound());

        assertThat(memberRepository.findByAuthUserId(AUTH_USER_ID)).isEmpty();
        assertThat(memberRepository.count()).isZero();
    }

    @TestConfiguration
    static class StubJwtDecoderConfig {

        @Bean
        @Primary
        JwtDecoder jwtDecoder() {
            return token ->
                    Jwt.withTokenValue(token)
                            .header("alg", "none")
                            .subject(
                                    NON_UUID_SUBJECT_TOKEN.equals(token)
                                            ? "not-a-uuid"
                                            : AUTH_USER_ID.toString())
                            .claim("email", "test@sscc.org")
                            .issuedAt(Instant.now())
                            .expiresAt(Instant.now().plusSeconds(60))
                            .build();
        }
    }
}
