package org.sscc.ssccopsserver.domain.member.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberGradeHistoryEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleAssignmentEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleClassificationEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberStatusHistoryEntity;
import org.sscc.ssccopsserver.domain.member.repository.AuthorityRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeHistoryRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleClassificationRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusHistoryRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.domain.member.repository.RoleAuthorityRelationRepository;
import org.sscc.ssccopsserver.domain.operation.service.SubWorkService;
import org.sscc.ssccopsserver.support.AuthorityFixture;
import org.sscc.ssccopsserver.support.MemberFixture;

/*
 * 회원 등급·상태 변경 API (#78).
 *
 * 확인하는 것은 세 가지다 — mbr이 바뀌고 이력이 한 줄 남는가(bfr/aftr·변경자·적용 일자),
 * 막아야 할 요청이 막히는가(같은 값·기준 코드 밖·미래 일자·종료 예정일·권한), 탈퇴 전이가
 * 남은 역할·담당 업무를 경고로 싣는가.
 *
 * **이력 저장이 실패했을 때의 롤백은 여기서 볼 수 없다.** 테스트에 @Transactional을 걸면 실제
 * 커밋·롤백이 일어나지 않기 때문이며, 그 규칙은 MemberChangeRollbackTest가 맡는다.
 *
 * 요청이 거절되는 테스트는 **요청 하나로 끝낸다.** 서비스 트랜잭션 안에서 예외가 나면 이
 * 테스트의 트랜잭션이 rollback-only가 되어 뒤따르는 요청이 UnexpectedRollbackException을
 * 만난다 (MemberQueryControllerTest·RoleAuthoritySelfLockTest와 같은 이유).
 *
 * 적용 일자가 주입된 Clock에서 오는지 확인해야 하므로 Clock을 고정한다 — 시스템 시각을 쓰고
 * 있었다면 기본 적용 일자 검증만 조용히 통과할 수 없다.
 *
 * 담당 하위 업무 건수는 SubWorkService를 대역으로 세워 정한다. 여기서 확인할 것은 '운영
 * 도메인에서 받아 온 숫자가 경고로 실리는가'이고, 그 숫자를 어떻게 세는지(완료 건 제외)는
 * 운영 도메인의 SubWorkServiceImplTest가 맡는다 — 회원 테스트가 상위 업무·유형·체크리스트
 * 픽스처를 통째로 세우기 시작하면 무엇을 검증하는 테스트인지 알 수 없게 된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MemberChangeControllerTest.ChangeTestConfig.class)
@Transactional
class MemberChangeControllerTest {

    private static final String MEMBERS = "/v1/members";

    /** 주입된 Clock이 말하는 오늘. 실제 시스템 날짜와 일부러 다른 값이다 */
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 20);

    /** MEMBER_MANAGE를 가진 주체 */
    private static final UUID MANAGER = UUID.randomUUID();

    /** 가입은 했으나 아무 권한도 없는 주체 */
    private static final UUID PLAIN_MEMBER = UUID.randomUUID();

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberGradeHistoryRepository memberGradeHistoryRepository;
    @Autowired private MemberStatusHistoryRepository memberStatusHistoryRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private AuthorityRepository authorityRepository;
    @Autowired private RoleAuthorityRelationRepository roleAuthorityRelationRepository;

    @MockitoBean private SubWorkService subWorkService;

    private Long managerId;
    private Long plainMemberId;
    private Long targetMemberId;

    @BeforeEach
    void setUp() {
        MemberEntity manager = saveMember(MANAGER, "20200001", "김도현");
        managerId = manager.getId();
        AuthorityFixture.grant(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                authorityRepository,
                roleAuthorityRelationRepository,
                manager,
                AuthorityCode.MEMBER_MANAGE);

        plainMemberId = saveMember(PLAIN_MEMBER, "20200002", "이서연").getId();

        /*
         * 변경 대상. 역할을 하나 붙여 두는 것은 탈퇴 전이의 경고를 확인하기 위해서다 —
         * 등급·상태를 바꾸는 것과 역할이 남아 있는 것은 별개라는 사실이 이 픽스처로 드러난다.
         */
        MemberEntity target = saveMember(UUID.randomUUID(), "20200003", "박준호");
        targetMemberId = target.getId();
        assignRole(target, "홍보국장");
    }

    /* ── 등급 변경 ───────────────────────────────────────── */

    /*
     * mbr 갱신과 이력이 한 건이다. 이력의 bfr/aftr가 정확하고 변경자가 **요청자**여야 한다 —
     * 요청 본문에 변경자를 실을 자리가 없다는 것이 이 API의 핵심이다.
     */
    @Test
    void gradeChangeUpdatesMemberAndRecordsHistory() throws Exception {
        mockMvc.perform(
                        changeGrade(
                                MANAGER,
                                """
                                {
                                  "aftrMbrGrdCd": "ASSOC",
                                  "grdChgRsnCn": "정기 심사 통과"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.member.memberId").value(targetMemberId))
                .andExpect(jsonPath("$.data.member.membershipGradeCode").value("ASSOC"))
                .andExpect(jsonPath("$.data.member.membershipGradeName").value("준회원"))
                // 변경 직후 상세를 다시 조회하지 않아도 되도록 최근 변경에 방금 남긴 줄이 들어 있다
                .andExpect(jsonPath("$.data.member.recentChanges[0].changeType").value("GRADE"))
                .andExpect(jsonPath("$.data.member.recentChanges[0].previousCode").value("TEMP"))
                .andExpect(jsonPath("$.data.member.recentChanges[0].newCode").value("ASSOC"))
                // 등급 변경은 조직을 떠나는 전이가 아니므로 경고가 없다
                .andExpect(jsonPath("$.data.warnings").isEmpty());

        assertThat(memberRepository.findById(targetMemberId).orElseThrow())
                .extracting(member -> member.getMembershipGrade().getCode())
                .isEqualTo("ASSOC");

        List<MemberGradeHistoryEntity> histories = gradeHistories();
        assertThat(histories).hasSize(1);
        MemberGradeHistoryEntity history = histories.get(0);
        assertThat(history.getPreviousGrade().getCode()).isEqualTo("TEMP");
        assertThat(history.getNewGrade().getCode()).isEqualTo("ASSOC");
        assertThat(history.getChangeReason()).isEqualTo("정기 심사 통과");
        assertThat(history.getChangedBy().getId()).isEqualTo(managerId);
        // 적용 일자를 생략하면 LocalDate.now()가 아니라 주입된 Clock의 오늘이다
        assertThat(history.getAppliedDate()).isEqualTo(TODAY);
    }

    /*
     * 본문에 변경자를 실어 보내도 이력에는 요청자가 남는다. 받아 주면 "누가 바꿨는가"를 스스로
     * 적어 넣을 수 있어 이력이 증거가 되지 못하므로, record에 그 자리를 만들지 않았다.
     */
    @Test
    void changerInRequestBodyIsIgnored() throws Exception {
        mockMvc.perform(
                        changeGrade(
                                MANAGER,
                                """
                                {
                                  "aftrMbrGrdCd": "ACTIVE",
                                  "chnrgMbrId": %d
                                }
                                """
                                        .formatted(plainMemberId)))
                .andExpect(status().isOk());

        assertThat(gradeHistories().get(0).getChangedBy().getId()).isEqualTo(managerId);
    }

    // 소급 입력은 실제로 일어나는 일이라 과거 일자는 통과한다
    @Test
    void gradeChangeAcceptsPastAppliedDate() throws Exception {
        mockMvc.perform(
                        changeGrade(
                                MANAGER,
                                """
                                {
                                  "aftrMbrGrdCd": "FULL",
                                  "grdAplcnYmd": "2026-03-02"
                                }
                                """))
                .andExpect(status().isOk());

        assertThat(gradeHistories().get(0).getAppliedDate()).isEqualTo(LocalDate.of(2026, 3, 2));
    }

    /*
     * 지금과 같은 등급으로의 변경은 거절하고 이력도 남기지 않는다. 통과시키면
     * "임시회원 → 임시회원" 행이 쌓여 실제 승급 시점을 찾을 수 없다.
     */
    @Test
    void sameGradeIsRejectedAndRecordsNothing() throws Exception {
        mockMvc.perform(
                        changeGrade(
                                MANAGER,
                                """
                                {"aftrMbrGrdCd": "TEMP"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NO_CHANGE"));

        assertThat(gradeHistories()).isEmpty();
        assertThat(memberRepository.findById(targetMemberId).orElseThrow())
                .extracting(member -> member.getMembershipGrade().getCode())
                .isEqualTo("TEMP");
    }

    // 기준 코드 자체에 없는 값. 형식 오류(VALIDATION_FAILED)와 나눠 안내할 수 있어야 한다
    @Test
    void unknownGradeCodeIs400InvalidCodeValue() throws Exception {
        mockMvc.perform(
                        changeGrade(
                                MANAGER,
                                """
                                {"aftrMbrGrdCd": "ALUMNI"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE_VALUE"));
    }

    /*
     * 미래 일자는 거절한다. mbr은 이 요청으로 지금 바뀌므로 받아들이면 "3월 1일부터 정회원"이
     * 이력에는 적혀 있는데 회원은 오늘부터 정회원인 상태가 되어 둘이 어긋난다.
     */
    @Test
    void futureAppliedDateIs400() throws Exception {
        mockMvc.perform(
                        changeGrade(
                                MANAGER,
                                """
                                {
                                  "aftrMbrGrdCd": "ASSOC",
                                  "grdAplcnYmd": "%s"
                                }
                                """
                                        .formatted(TODAY.plusDays(1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void missingGradeCodeIs400() throws Exception {
        mockMvc.perform(changeGrade(MANAGER, "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /* ── 상태 변경 ───────────────────────────────────────── */

    // 휴학은 끝이 정해진 상태라 종료 예정일을 함께 남길 수 있다
    @Test
    void statusChangeUpdatesMemberAndRecordsHistoryWithExpectedEndDate() throws Exception {
        mockMvc.perform(
                        changeStatus(
                                MANAGER,
                                """
                                {
                                  "aftrMbrSttsCd": "LEAVE",
                                  "sttsAplcnYmd": "2026-08-01",
                                  "sttsEndPrnmntYmd": "2027-03-02",
                                  "sttsChgRsnCn": "군 입대 전 학기 휴학"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.member.membershipStatusCode").value("LEAVE"))
                .andExpect(jsonPath("$.data.member.membershipStatusName").value("일반휴학"))
                .andExpect(jsonPath("$.data.member.recentChanges[0].changeType").value("STATUS"))
                .andExpect(
                        jsonPath("$.data.member.recentChanges[0].previousCode").value("ENROLLED"))
                .andExpect(jsonPath("$.data.member.recentChanges[0].newCode").value("LEAVE"))
                // 휴학은 조직을 떠나는 전이가 아니다 — 역할이 남아 있어도 경고하지 않는다
                .andExpect(jsonPath("$.data.warnings").isEmpty());

        assertThat(memberRepository.findById(targetMemberId).orElseThrow())
                .extracting(member -> member.getMembershipStatus().getCode())
                .isEqualTo("LEAVE");

        List<MemberStatusHistoryEntity> histories = statusHistories();
        assertThat(histories).hasSize(1);
        MemberStatusHistoryEntity history = histories.get(0);
        assertThat(history.getPreviousStatus().getCode()).isEqualTo("ENROLLED");
        assertThat(history.getNewStatus().getCode()).isEqualTo("LEAVE");
        assertThat(history.getAppliedDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(history.getExpectedEndDate()).isEqualTo(LocalDate.of(2027, 3, 2));
        assertThat(history.getChangedBy().getId()).isEqualTo(managerId);
    }

    @Test
    void sameStatusIsRejectedAndRecordsNothing() throws Exception {
        mockMvc.perform(
                        changeStatus(
                                MANAGER,
                                """
                                {"aftrMbrSttsCd": "ENROLLED"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NO_CHANGE"));

        assertThat(statusHistories()).isEmpty();
    }

    /*
     * 끝이 정해지지 않은 상태에 실려 온 종료 예정일은 조용히 버리지 않고 거절한다. 이력 행은
     * updatable = false라 나중에 채워 넣을 경로가 없어, 버리면 운영자는 적어 넣었다고 믿는데
     * 어디에도 남지 않는다.
     */
    @Test
    void expectedEndDateOnStatusWithoutEndIsRejected() throws Exception {
        mockMvc.perform(
                        changeStatus(
                                MANAGER,
                                """
                                {
                                  "aftrMbrSttsCd": "GRADUATED",
                                  "sttsEndPrnmntYmd": "2027-03-02"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(statusHistories()).isEmpty();
    }

    // 시작하기 전에 끝나는 상태는 성립하지 않는다
    @Test
    void expectedEndDateBeforeAppliedDateIsRejected() throws Exception {
        mockMvc.perform(
                        changeStatus(
                                MANAGER,
                                """
                                {
                                  "aftrMbrSttsCd": "MIL_LEAVE",
                                  "sttsAplcnYmd": "2026-08-01",
                                  "sttsEndPrnmntYmd": "2026-07-31"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /* ── 탈퇴·제명 전이의 경고 ───────────────────────────── */

    /*
     * 역할을 끝내지도, 담당 업무를 회수하지도 **않는다.** 대신 남아 있는 것들을 숫자로 실어
     * 화면이 사람에게 알리게 한다 — 자동 정리는 운영 규칙이 정해진 뒤의 일이다.
     */
    @Test
    void withdrawalCarriesRemainingRolesAndSubWorksAsWarnings() throws Exception {
        given(subWorkService.countOngoingByOwner(targetMemberId)).willReturn(3L);

        mockMvc.perform(
                        changeStatus(
                                MANAGER,
                                """
                                {
                                  "aftrMbrSttsCd": "WITHDRAWN",
                                  "sttsChgRsnCn": "본인 요청"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.member.membershipStatusCode").value("WITHDRAWN"))
                .andExpect(jsonPath("$.data.warnings.length()").value(2))
                .andExpect(jsonPath("$.data.warnings[0].code").value("CURRENT_ROLES_REMAIN"))
                .andExpect(jsonPath("$.data.warnings[0].count").value(1))
                .andExpect(jsonPath("$.data.warnings[1].code").value("ASSIGNED_SUB_WORKS_REMAIN"))
                .andExpect(jsonPath("$.data.warnings[1].count").value(3))
                // 경고일 뿐 아무것도 정리하지 않았다 — 역할은 그대로 남아 있다
                .andExpect(jsonPath("$.data.member.roles.length()").value(1));

        assertThat(memberRoleAssignmentRepository.countCurrentByMemberId(targetMemberId, TODAY))
                .isEqualTo(1);
    }

    /*
     * 남은 것이 없으면 건수 0짜리 줄을 만들지 않고 아예 빼 버린다 — 화면이 "역할 0건이
     * 있습니다"를 그리지 않게 하려면 서버가 그 줄을 내리지 않는 편이 간단하다.
     * 역할도 담당 업무도 없는 회원(이서연)을 제명해 확인한다.
     */
    @Test
    void expulsionWithNothingLeftCarriesNoWarnings() throws Exception {
        mockMvc.perform(
                        changeStatus(
                                MANAGER,
                                """
                                {"aftrMbrSttsCd": "EXPELLED"}
                                """,
                                plainMemberId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.warnings").isEmpty());
    }

    /* ── 인가 계단 ───────────────────────────────────────── */

    @Test
    void changeRequiresMemberManage() throws Exception {
        mockMvc.perform(
                        changeGrade(
                                PLAIN_MEMBER,
                                """
                                {"aftrMbrGrdCd": "ASSOC"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void requestWithoutTokenIs401() throws Exception {
        mockMvc.perform(
                        post(MEMBERS + "/" + targetMemberId + "/grade-changes")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"aftrMbrGrdCd\": \"ASSOC\"}"))
                .andExpect(status().isUnauthorized());
    }

    // 없는 회원은 404다. 권한 부족을 404로 감추지 않는 것과는 다른 이야기다 (VR-M10)
    @Test
    void unknownMemberIs404() throws Exception {
        mockMvc.perform(
                        authorized(
                                        post(
                                                MEMBERS
                                                        + "/"
                                                        + (targetMemberId + 9999)
                                                        + "/grade-changes"),
                                        MANAGER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"aftrMbrGrdCd\": \"ASSOC\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /* ── 헬퍼 ────────────────────────────────────────────── */

    private MockHttpServletRequestBuilder changeGrade(UUID subject, String body) {
        return authorized(post(MEMBERS + "/" + targetMemberId + "/grade-changes"), subject)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private MockHttpServletRequestBuilder changeStatus(UUID subject, String body) {
        return changeStatus(subject, body, targetMemberId);
    }

    private MockHttpServletRequestBuilder changeStatus(UUID subject, String body, Long memberId) {
        return authorized(post(MEMBERS + "/" + memberId + "/status-changes"), subject)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static MockHttpServletRequestBuilder authorized(
            MockHttpServletRequestBuilder builder, UUID authUserId) {
        return builder.header("Authorization", "Bearer " + authUserId);
    }

    private List<MemberGradeHistoryEntity> gradeHistories() {
        return memberGradeHistoryRepository.findByMemberIdOrderByCreatedAtDescIdDesc(
                targetMemberId, PageRequest.of(0, 10));
    }

    private List<MemberStatusHistoryEntity> statusHistories() {
        return memberStatusHistoryRepository.findByMemberIdOrderByCreatedAtDescIdDesc(
                targetMemberId, PageRequest.of(0, 10));
    }

    private MemberEntity saveMember(UUID authUserId, String studentNumber, String name) {
        return MemberFixture.save(
                memberRepository,
                memberGradeRepository,
                memberStatusRepository,
                authUserId,
                studentNumber,
                name,
                studentNumber + "@sscc.org");
    }

    // 권한이 붙지 않은 역할. 여기서 보고 싶은 것은 인가가 아니라 '남아 있는 역할의 건수'다
    private void assignRole(MemberEntity member, String roleName) {
        MemberRoleClassificationEntity position =
                memberRoleClassificationRepository.findById("POSITION").orElseThrow();
        MemberRoleEntity role =
                memberRoleRepository.save(MemberRoleEntity.create(99, roleName, position));
        memberRoleAssignmentRepository.save(
                MemberRoleAssignmentEntity.create(member, role, TODAY.minusYears(1), true));
    }

    @TestConfiguration
    static class ChangeTestConfig {

        /*
         * 토큰 문자열을 그대로 sub로 쓴다 — 한 클래스 안에서 권한 있는 회원과 권한 없는 회원을
         * 번갈아 흉내 내야 하기 때문이다 (MemberQueryControllerTest와 같은 방식).
         */
        @Bean
        @Primary
        JwtDecoder jwtDecoder() {
            return token ->
                    Jwt.withTokenValue(token)
                            .header("alg", "none")
                            .subject(token)
                            .claim("email", token + "@sscc.org")
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
