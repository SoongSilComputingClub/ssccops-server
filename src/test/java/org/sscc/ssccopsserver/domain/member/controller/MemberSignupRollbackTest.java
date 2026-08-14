package org.sscc.ssccopsserver.domain.member.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeHistoryRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusHistoryRepository;

/*
 * 가입이 도중에 실패하면 mbr과 이력이 함께 사라져야 한다는 것만 확인한다.
 *
 * 테스트에 @Transactional을 걸면 실제 커밋·롤백이 일어나지 않아 이 규칙을 검증할 수 없다.
 * 그래서 이 클래스만 트랜잭션 없이 돌리고, 상태 이력 저장을 실패시켜 회원 INSERT 이후에
 * 예외가 나는 상황을 만든다 — 회원만 남는 반쪽 가입이 생기는지가 확인 대상이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MemberSignupRollbackTest.StubJwtDecoderConfig.class)
class MemberSignupRollbackTest {

    private static final UUID AUTH_USER_ID = UUID.randomUUID();

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeHistoryRepository memberGradeHistoryRepository;

    @MockitoBean private MemberStatusHistoryRepository memberStatusHistoryRepository;

    @Test
    void failureAfterMemberInsertRollsBackEverything() throws Exception {
        given(memberStatusHistoryRepository.save(any()))
                .willThrow(new IllegalStateException("상태 이력 저장 실패"));

        mockMvc.perform(
                        post("/v1/members/signup")
                                .header("Authorization", "Bearer any-token")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "name": "김도현",
                                          "phoneNumber": "010-1234-5678",
                                          "memberStatusCode": "ENROLLED",
                                          "studentNumber": "20200001",
                                          "departmentName": "컴퓨터학부",
                                          "academicYear": 3
                                        }
                                        """))
                .andExpect(status().isInternalServerError());

        assertThat(memberRepository.findByAuthUserId(AUTH_USER_ID)).isEmpty();
        assertThat(memberRepository.count()).isZero();
        assertThat(memberGradeHistoryRepository.count()).isZero();
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
                            .claim("email", "test@sscc.org")
                            .claim("user_metadata", Map.of("full_name", "김도현"))
                            .claim("app_metadata", Map.of("provider", "google"))
                            .issuedAt(Instant.now())
                            .expiresAt(Instant.now().plusSeconds(60))
                            .build();
        }
    }
}
