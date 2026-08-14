package org.sscc.ssccopsserver.domain.member.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.code.MemberGradeCode;
import org.sscc.ssccopsserver.domain.member.code.MemberStatusCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberGradeHistoryEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberStatusHistoryEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeHistoryRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusHistoryRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.support.MemberFixture;

/*
 * 회원가입 API. 인증 필터체인을 그대로 태우기 위해 JwtDecoder만 고정 Jwt를 반환하도록 대체한다.
 *
 * 각 테스트는 트랜잭션 롤백되므로 회원을 만들지 않은 테스트는 그대로 미가입 상태에서 시작한다 —
 * 가입 API가 검증해야 하는 상태가 바로 그것이다.
 *
 * 가입일이 주입된 Clock에서 오는지 확인해야 하므로 Clock도 고정값으로 바꾼다. 시스템 시각을
 * 쓰고 있었다면 이 검증만 조용히 통과할 수 없다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MemberControllerTest.SignupTestConfig.class)
@Transactional
class MemberControllerTest {

    private static final UUID AUTH_USER_ID = UUID.randomUUID();
    private static final String EMAIL = "test@sscc.org";
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 2);

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberGradeHistoryRepository memberGradeHistoryRepository;
    @Autowired private MemberStatusHistoryRepository memberStatusHistoryRepository;

    @Test
    void enrolledMemberSignsUpAsTemporaryMember() throws Exception {
        mockMvc.perform(
                        signup(
                                """
                                {
                                  "name": "김도현",
                                  "phoneNumber": "010-1234-5678",
                                  "memberStatusCode": "ENROLLED",
                                  "studentNumber": "20200001",
                                  "departmentName": "컴퓨터학부",
                                  "academicYear": 3,
                                  "generationNumber": 31
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.studentNumber").value("20200001"))
                .andExpect(jsonPath("$.data.name").value("김도현"))
                .andExpect(jsonPath("$.data.departmentName").value("컴퓨터학부"))
                .andExpect(jsonPath("$.data.academicYear").value(3))
                .andExpect(jsonPath("$.data.generationNumber").value(31))
                // 이메일은 요청이 아니라 토큰에서 온다
                .andExpect(jsonPath("$.data.email").value(EMAIL))
                .andExpect(jsonPath("$.data.membershipGradeCode").value("TEMP"))
                .andExpect(jsonPath("$.data.membershipGradeName").value("임시회원"))
                .andExpect(jsonPath("$.data.membershipStatusCode").value("ENROLLED"))
                .andExpect(jsonPath("$.data.membershipStatusName").value("재학"))
                // 가입 직후에는 역할이 없다 (#9 역할 인가의 전제)
                .andExpect(jsonPath("$.data.roles").isEmpty());

        MemberEntity member = memberRepository.findByAuthUserId(AUTH_USER_ID).orElseThrow();
        assertThat(member.getEmail()).isEqualTo(EMAIL);
        assertThat(member.getMembershipGrade().getCode()).isEqualTo(MemberGradeCode.TEMP.code());
        // 가입일은 LocalDate.now()가 아니라 주입된 Clock에서 온다
        assertThat(member.getJoinDate()).isEqualTo(TODAY);
    }

    // 생성된 자원의 위치를 Location으로 알려준다 (AP-01)
    @Test
    void signupReturnsCreatedWithLocationOfNewMember() throws Exception {
        MvcResult result =
                mockMvc.perform(signup(enrolledBody())).andExpect(status().isCreated()).andReturn();

        Long memberId = memberRepository.findByAuthUserId(AUTH_USER_ID).orElseThrow().getId();
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/v1/members/" + memberId);
        assertThat(result.getResponse().getContentAsString()).contains("\"memberId\":" + memberId);
    }

    // 등급·상태의 최초 부여도 이력에 남아야 회원 상세의 변경이력이 시작점을 보여줄 수 있다
    @Test
    void signupRecordsInitialGradeAndStatusHistory() throws Exception {
        mockMvc.perform(signup(enrolledBody())).andExpect(status().isCreated());

        MemberEntity member = memberRepository.findByAuthUserId(AUTH_USER_ID).orElseThrow();

        assertThat(memberGradeHistoryRepository.findAll())
                .singleElement()
                .satisfies(
                        history -> {
                            assertThat(history.getMember().getId()).isEqualTo(member.getId());
                            assertThat(history.getPreviousGrade()).isNull();
                            assertThat(history.getNewGrade().getCode())
                                    .isEqualTo(MemberGradeCode.TEMP.code());
                            assertThat(history.getAppliedDate()).isEqualTo(TODAY);
                            assertThat(changedById(history)).isEqualTo(member.getId());
                        });

        assertThat(memberStatusHistoryRepository.findAll())
                .singleElement()
                .satisfies(
                        history -> {
                            assertThat(history.getPreviousStatus()).isNull();
                            assertThat(history.getNewStatus().getCode())
                                    .isEqualTo(MemberStatusCode.ENROLLED.code());
                            assertThat(changedById(history)).isEqualTo(member.getId());
                        });
    }

    /*
     * 졸업 회원은 학번·학과·학년 없이도 가입된다. 학번은 빈 문자열이 아니라 NULL로 저장돼야 한다 —
     * 빈 문자열이면 두 번째 졸업 회원부터 uk_mbr_student_number에 걸린다.
     */
    @Test
    void graduatedMemberSignsUpWithoutStudentNumber() throws Exception {
        mockMvc.perform(
                        signup(
                                """
                                {
                                  "name": "이서연",
                                  "phoneNumber": "010-0000-0000",
                                  "memberStatusCode": "GRADUATED",
                                  "studentNumber": ""
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.studentNumber").isEmpty())
                .andExpect(jsonPath("$.data.membershipStatusCode").value("GRADUATED"))
                // 기수를 내지 않으면 미배정(0)이다. 운영진이 사후에 배정한다
                .andExpect(jsonPath("$.data.generationNumber").value(0));

        assertThat(memberRepository.findByAuthUserId(AUTH_USER_ID).orElseThrow().getStudentNumber())
                .isNull();
    }

    @Test
    void enrolledMemberWithoutAcademicProfileIsRejected() throws Exception {
        mockMvc.perform(
                        signup(
                                """
                                {
                                  "name": "김도현",
                                  "phoneNumber": "010-1234-5678",
                                  "memberStatusCode": "ENROLLED"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(memberRepository.count()).isZero();
    }

    @Test
    void missingRequiredFieldIsRejected() throws Exception {
        mockMvc.perform(
                        signup(
                                """
                                {
                                  "name": "  ",
                                  "memberStatusCode": "GRADUATED"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // 이미 가입한 계정의 재요청. 회원이 하나 더 생기지 않는 것까지 확인한다
    @Test
    void alreadySignedUpAccountIsRejected() throws Exception {
        MemberFixture.save(
                memberRepository,
                memberGradeRepository,
                memberStatusRepository,
                AUTH_USER_ID,
                "20190001",
                "김도현",
                EMAIL);

        mockMvc.perform(signup(enrolledBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_SIGNED_UP"));

        assertThat(memberRepository.count()).isEqualTo(1);
    }

    /*
     * 학번이 이미 쓰이고 있으면 거절한다. 이관 회원(auth_user_id가 NULL인 행)도 마찬가지다 —
     * 학번만 알면 남의 계정을 가로챌 수 있어 자동 연결하지 않기로 했다.
     */
    @Test
    void duplicatedStudentNumberIsRejected() throws Exception {
        MemberFixture.save(
                memberRepository,
                memberGradeRepository,
                memberStatusRepository,
                null,
                "20200001",
                "이서연",
                "other@sscc.org");

        mockMvc.perform(signup(enrolledBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STUDENT_NUMBER_DUPLICATED"));

        assertThat(memberRepository.findByAuthUserId(AUTH_USER_ID)).isEmpty();
        assertThat(memberGradeHistoryRepository.count()).isZero();
    }

    // 기준 코드에는 있으나 가입 시에는 고를 수 없는 상태
    @Test
    void statusNotSelectableAtSignupIsRejected() throws Exception {
        mockMvc.perform(
                        signup(
                                """
                                {
                                  "name": "김도현",
                                  "phoneNumber": "010-1234-5678",
                                  "memberStatusCode": "WITHDRAWN"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(memberRepository.count()).isZero();
    }

    // 기준 코드 자체에 없는 값. 입력값 형식 오류와 구분해 안내할 수 있어야 한다
    @Test
    void unknownStatusCodeIsRejected() throws Exception {
        mockMvc.perform(
                        signup(
                                """
                                {
                                  "name": "김도현",
                                  "phoneNumber": "010-1234-5678",
                                  "memberStatusCode": "ALUMNI"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE_VALUE"));
    }

    // 토큰 없는 호출은 가입 이전에 인증에서 끊긴다 (403 SIGNUP_REQUIRED가 아니다)
    @Test
    void requestWithoutTokenReturns401() throws Exception {
        mockMvc.perform(
                        post("/v1/members/signup")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(enrolledBody()))
                .andExpect(status().isUnauthorized());
    }

    private static String enrolledBody() {
        return """
                {
                  "name": "김도현",
                  "phoneNumber": "010-1234-5678",
                  "memberStatusCode": "ENROLLED",
                  "studentNumber": "20200001",
                  "departmentName": "컴퓨터학부",
                  "academicYear": 3
                }
                """;
    }

    private static MockHttpServletRequestBuilder signup(String body) {
        return post("/v1/members/signup")
                .header("Authorization", "Bearer any-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static Long changedById(MemberGradeHistoryEntity history) {
        return history.getChangedBy() == null ? null : history.getChangedBy().getId();
    }

    private static Long changedById(MemberStatusHistoryEntity history) {
        return history.getChangedBy() == null ? null : history.getChangedBy().getId();
    }

    @TestConfiguration
    static class SignupTestConfig {

        @Bean
        @Primary
        JwtDecoder jwtDecoder() {
            return token ->
                    Jwt.withTokenValue(token)
                            .header("alg", "none")
                            .subject(AUTH_USER_ID.toString())
                            .claim("email", EMAIL)
                            .claim("user_metadata", Map.of("full_name", "김도현"))
                            .claim("app_metadata", Map.of("provider", "google"))
                            .issuedAt(Instant.now())
                            .expiresAt(Instant.now().plusSeconds(60))
                            .build();
        }

        /*
         * ClockConfig가 정의한 clock 빈과 이름이 겹치지 않게 다른 이름으로 둔다 —
         * 같은 이름이면 빈 정의 덮어쓰기가 막혀 있어 컨텍스트 기동부터 실패한다.
         */
        @Bean
        @Primary
        Clock fixedClock() {
            ZoneOffset kst = ZoneOffset.ofHours(9);
            return Clock.fixed(TODAY.atStartOfDay(kst).toInstant(), kst);
        }
    }
}
