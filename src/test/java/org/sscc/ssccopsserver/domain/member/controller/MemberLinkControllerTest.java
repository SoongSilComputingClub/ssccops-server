package org.sscc.ssccopsserver.domain.member.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.code.MemberGradeCode;
import org.sscc.ssccopsserver.domain.member.code.MemberStatusCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.AuthorityRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleClassificationRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.domain.member.repository.RoleAuthorityRelationRepository;
import org.sscc.ssccopsserver.domain.member.service.MemberLinkAttemptLimiter;
import org.sscc.ssccopsserver.support.AuthorityFixture;
import org.sscc.ssccopsserver.support.MemberFixture;

/*
 * 이관 회원 계정 연결 API (#86). 인증 필터체인을 그대로 태우기 위해 JwtDecoder만 고정 Jwt를
 * 반환하도록 대체한다 (MemberControllerTest와 같은 방식).
 *
 * ── 실패하는 요청은 테스트당 하나다 ────────────────────────────
 * 서비스가 예외를 던지면 참여 트랜잭션이 rollback-only로 표시되어, 같은 테스트에서 이어지는
 * 요청은 UnexpectedRollbackException을 만난다. 여러 입력으로 같은 실패를 확인해야 하는 곳은
 * @ParameterizedTest로 나눠 각 입력이 자기 트랜잭션에서 돌게 한다.
 *
 * ── 시도 횟수 카운터를 매번 지운다 ────────────────────────────
 * MemberLinkAttemptLimiter는 컨텍스트에 하나뿐인 빈이라 실패 카운터가 테스트 사이에 그대로
 * 넘어간다. 이 클래스에는 실패를 확인하는 테스트가 여럿이라 지우지 않으면 뒷 테스트가 404가
 * 아니라 429를 받는다 — 잠금 자체는 MemberLinkAttemptLimitTest가 따로 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MemberLinkControllerTest.LinkTestConfig.class)
@Transactional
class MemberLinkControllerTest {

    private static final UUID AUTH_USER_ID = UUID.randomUUID();
    private static final String EMAIL = "newcomer@sscc.org";
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 2);

    // 명부(CSV 이관)에 이미 들어와 있는 회원. 연락처는 하이픈이 든 표기로 저장돼 있다
    private static final String ROSTER_STUDENT_NUMBER = "20190123";
    private static final String ROSTER_NAME = "김도현";
    private static final String ROSTER_PHONE = "010-1111-2222";
    private static final int ROSTER_GENERATION = 25;

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private AuthorityRepository authorityRepository;
    @Autowired private RoleAuthorityRelationRepository roleAuthorityRelationRepository;
    @Autowired private MemberLinkAttemptLimiter linkAttemptLimiter;

    private MemberEntity rosterMember;

    /** 연결 전 mbr 행 수. "연결로 회원이 늘었는가"는 이 값과 비교해 본다 */
    private long baselineMemberCount;

    @BeforeEach
    void seedRosterMember() {
        linkAttemptLimiter.reset(AUTH_USER_ID);
        rosterMember = saveRosterMember(ROSTER_STUDENT_NUMBER, ROSTER_NAME, ROSTER_PHONE);
        baselineMemberCount = memberRepository.count();
    }

    /*
     * 연결의 본체. **회원 행이 늘지 않고 auth_user_id만 채워진다** (BR-M51) — 같은 사람이 두
     * 행이 되는 것을 막는 것이 이 API의 존재 이유다.
     */
    @Test
    void migratedMemberIsLinkedWithoutCreatingAnotherRow() throws Exception {
        Long rosterMemberId = rosterMember.getId();

        mockMvc.perform(link(body(ROSTER_STUDENT_NUMBER, ROSTER_NAME, ROSTER_PHONE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                // 명부의 그 행이다 — 새로 만든 회원이 아니다
                .andExpect(jsonPath("$.data.memberId").value(rosterMemberId))
                .andExpect(jsonPath("$.data.studentNumber").value(ROSTER_STUDENT_NUMBER))
                .andExpect(jsonPath("$.data.name").value(ROSTER_NAME));

        assertThat(memberRepository.count()).isEqualTo(baselineMemberCount);
        assertThat(memberRepository.findByAuthUserId(AUTH_USER_ID))
                .get()
                .extracting(MemberEntity::getId)
                .isEqualTo(rosterMemberId);
    }

    /*
     * 연결 응답은 가입·세션 조회와 같은 MemberProfileResponse이며 **명부의 값이 그대로 실린다** —
     * 웹이 연결 직후 세션을 다시 조회하지 않아도 되게 하는 것이 그 설계의 의도다.
     *
     * 등급이 TEMP가 아니라는 것이 요점이다. 가입 경로를 탔다면 임시회원으로 굳었을 사람이고,
     * 기수·역할·capabilities도 마찬가지로 이관된 값 그대로여야 한다.
     */
    @Test
    void linkedProfileCarriesMigratedGradeGenerationRolesAndCapabilities() throws Exception {
        AuthorityFixture.grant(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                authorityRepository,
                roleAuthorityRelationRepository,
                rosterMember,
                AuthorityCode.MEMBER_MANAGE,
                TODAY.minusYears(1),
                null);

        mockMvc.perform(link(body(ROSTER_STUDENT_NUMBER, ROSTER_NAME, ROSTER_PHONE)))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data.membershipGradeCode").value(MemberGradeCode.FULL.code()))
                .andExpect(jsonPath("$.data.generationNumber").value(ROSTER_GENERATION))
                .andExpect(jsonPath("$.data.roles.length()").value(1))
                .andExpect(
                        jsonPath("$.data.capabilities")
                                .value(hasItem(AuthorityCode.MEMBER_MANAGE.code())));
    }

    /*
     * 학번만 맞고 나머지가 틀리면 404다. **응답이 어느 항목이 틀렸는지 알려 주지 않는다**
     * (VR-M23) — 알려 주면 학번을 넣어 보는 것만으로 명부의 이름을 확인할 수 있다.
     */
    @Test
    void linkFailsWhenOnlyStudentNumberMatches() throws Exception {
        MvcResult result =
                mockMvc.perform(link(body(ROSTER_STUDENT_NUMBER, "이서연", "010-9999-9999")))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.code").value("MEMBER_LINK_FAILED"))
                        .andExpect(jsonPath("$.data").isEmpty())
                        .andReturn();

        // 어느 항목이 맞았는지·틀렸는지가 본문 어디에도 없어야 한다
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain(ROSTER_NAME).doesNotContain(ROSTER_PHONE);
        assertThat(body).doesNotContain("학번").doesNotContain("이름").doesNotContain("연락처");
    }

    /*
     * 연락처의 하이픈 유무는 가리지 않는다. 명부에는 '010-1111-2222'로 들어 있고 본인은
     * '01011112222'로 치는 것이 정상인데, 그대로 비교하면 본인인데도 연결되지 않는다.
     * 정규화 규칙이 서버 한 곳(MemberLinkPolicy)에 있다는 것을 입력 여러 벌로 확인한다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"010-1111-2222", "01011112222", "010 1111 2222", " 010-1111-2222 "})
    void phoneNumberFormatDoesNotAffectLinking(String telno) throws Exception {
        mockMvc.perform(link(body(ROSTER_STUDENT_NUMBER, "  " + ROSTER_NAME + "  ", telno)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberId").value(rosterMember.getId()));
    }

    /*
     * 연락처가 NULL인 이관 회원은 **어떤 입력으로도** 연결되지 않는다.
     *
     * CSV 이관(#84)이 연락처 누락을 오류가 아니라 경고로 통과시키므로 이런 행이 실제로 생긴다.
     * 정규화 결과가 양쪽 다 비어 있을 때 일치로 보면 연락처를 확인하지 않은 채 학번·이름만으로
     * 연결되어 A안이 통째로 무너진다 — 운영진이 연락처를 채워 넣는 것이 유일한 길이다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"010-0000-0000", "01000000000", "0"})
    void migratedMemberWithoutPhoneNumberIsNeverLinked(String telno) throws Exception {
        MemberEntity noPhone = saveRosterMember("20190999", "이서연", null);

        mockMvc.perform(link(body("20190999", "이서연", telno)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEMBER_LINK_FAILED"));

        assertThat(memberRepository.findById(noPhone.getId()).orElseThrow().getAuthUserId())
                .isNull();
    }

    // 이미 다른 계정이 가져간 명부 회원. 3종이 다 맞아도 그 행은 더는 연결 대상이 아니다
    @Test
    void alreadyLinkedMemberIsRejected() throws Exception {
        rosterMember.assignAuthUserId(UUID.randomUUID());
        memberRepository.saveAndFlush(rosterMember);

        mockMvc.perform(link(body(ROSTER_STUDENT_NUMBER, ROSTER_NAME, ROSTER_PHONE)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER_ALREADY_LINKED"));
    }

    // 이미 가입을 마친 계정은 연결 대상이 아니다. 가입 경로가 쓰는 코드를 그대로 재사용한다
    @Test
    void alreadySignedUpAccountIsRejected() throws Exception {
        MemberFixture.save(
                memberRepository,
                memberGradeRepository,
                memberStatusRepository,
                AUTH_USER_ID,
                "20200001",
                "박준호",
                EMAIL);

        mockMvc.perform(link(body(ROSTER_STUDENT_NUMBER, ROSTER_NAME, ROSTER_PHONE)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_SIGNED_UP"));
    }

    // 세 값이 모두 필수다. 하나라도 비면 명부를 보기 전에 400으로 끊긴다
    @Test
    void missingRequiredFieldIsRejected() throws Exception {
        mockMvc.perform(link(body(ROSTER_STUDENT_NUMBER, ROSTER_NAME, "  ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // 토큰 없는 호출은 연결 이전에 인증에서 끊긴다 (403 SIGNUP_REQUIRED가 아니다)
    @Test
    void requestWithoutTokenReturns401() throws Exception {
        mockMvc.perform(
                        post("/v1/members/link")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(ROSTER_STUDENT_NUMBER, ROSTER_NAME, ROSTER_PHONE)))
                .andExpect(status().isUnauthorized());
    }

    /*
     * 회귀 — 명부에 없는 사람은 종전대로 가입 경로로 회원이 된다 (#21).
     *
     * 연결 API를 열었다고 가입이 달라지지 않는다. 학번이 명부에 없으면 STUDENT_NUMBER_DUPLICATED에
     * 걸릴 이유도 없고, 새 mbr 행이 하나 생기는 것이 맞다.
     */
    @Test
    void personOutsideTheRosterStillSignsUpThroughTheSignupPath() throws Exception {
        mockMvc.perform(
                        post("/v1/members/signup")
                                .header("Authorization", "Bearer any-token")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "name": "이서연",
                                          "phoneNumber": "010-3333-4444",
                                          "memberStatusCode": "ENROLLED",
                                          "studentNumber": "20260001",
                                          "departmentName": "컴퓨터학부",
                                          "academicYear": 1
                                        }
                                        """))
                .andExpect(status().isCreated())
                .andExpect(
                        jsonPath("$.data.membershipGradeCode").value(MemberGradeCode.TEMP.code()));

        assertThat(memberRepository.count()).isEqualTo(baselineMemberCount + 1);
    }

    /*
     * 명부에 이미 들어와 있는 회원 한 건. auth_user_id는 비어 있고(아직 로그인한 적이 없다)
     * 등급은 가입으로는 나올 수 없는 값(정회원)이라 "명부의 값이 그대로 남는가"를 볼 수 있다.
     */
    private MemberEntity saveRosterMember(String studentNumber, String name, String phoneNumber) {
        return memberRepository.saveAndFlush(
                MemberEntity.create(
                        studentNumber,
                        ROSTER_GENERATION,
                        name,
                        "컴퓨터학부",
                        4,
                        phoneNumber,
                        // 이관 명부에는 이메일이 없을 수 있다 (CSV 매핑 대상이 아니다)
                        null,
                        memberGradeRepository.findById(MemberGradeCode.FULL.code()).orElseThrow(),
                        memberStatusRepository
                                .findById(MemberStatusCode.ENROLLED.code())
                                .orElseThrow(),
                        LocalDate.of(2019, 3, 1)));
    }

    private static String body(String stdntNo, String mbrNm, String telno) {
        return """
                {
                  "stdntNo": "%s",
                  "mbrNm": "%s",
                  "telno": "%s"
                }
                """
                .formatted(stdntNo, mbrNm, telno);
    }

    private static MockHttpServletRequestBuilder link(String body) {
        return post("/v1/members/link")
                .header("Authorization", "Bearer any-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    @TestConfiguration
    static class LinkTestConfig {

        @Bean
        @Primary
        JwtDecoder jwtDecoder() {
            return token ->
                    Jwt.withTokenValue(token)
                            .header("alg", "none")
                            .subject(AUTH_USER_ID.toString())
                            .claim("email", EMAIL)
                            .claim("user_metadata", Map.of("full_name", ROSTER_NAME))
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
