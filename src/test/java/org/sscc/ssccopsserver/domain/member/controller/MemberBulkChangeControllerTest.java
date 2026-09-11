package org.sscc.ssccopsserver.domain.member.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.willAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.dto.MemberBulkGradeChangeRequest;
import org.sscc.ssccopsserver.domain.member.dto.MemberBulkStatusChangeRequest;
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
import org.sscc.ssccopsserver.domain.member.service.MemberBulkChangeServiceImpl;
import org.sscc.ssccopsserver.domain.member.service.MemberSubWorkLoadProvider;
import org.sscc.ssccopsserver.support.AuthorityFixture;
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

/*
 * 회원 등급·상태 일괄 변경 API (#338).
 *
 * ── 왜 @Transactional이 없는가 ──────────────────────────────────
 * 확인의 중심이 **"한 명의 실패가 앞선 성공을 되돌리지 않는다"**이고, 그것은 회원마다 실제로
 * 커밋·롤백이 일어나야만 확인할 수 있다. 테스트에 트랜잭션을 걸면 서비스의 @Transactional이
 * 거기에 **참여**해 버려 경계가 통째로 하나가 되고 — 확인하려는 규칙이 성립하지 않는 것은
 * 물론이고 — 실패한 요청 뒤의 모든 요청이 UnexpectedRollbackException을 만난다
 * (MemberImportExecutionTest·MemberSignupRollbackTest와 같은 이유).
 *
 * 대신 뒷정리를 손으로 한다. 시드(V3)가 넣은 역할·권한은 건드리지 않고 이 클래스가 만든
 * 것('역할:' 접두사)만 지운다.
 *
 * 담당 하위 업무 건수는 MemberSubWorkLoadProvider 대역으로 정한다(ssccops#242의 포트) —
 * 기본값이 0이라 역할만 붙여 두면 경고가 역할 한 줄로 좁혀져 무엇을 보는 테스트인지 분명해진다.
 * 그 대역은 "한 명만 저장 도중에 터뜨리는" 장치이기도 하다(아래).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
class MemberBulkChangeControllerTest {

    private static final String GRADE_CHANGES = "/v1/members/grade-changes";
    private static final String STATUS_CHANGES = "/v1/members/status-changes";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
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

    @MockitoBean private MemberSubWorkLoadProvider subWorkLoadProvider;

    private UUID managerToken;
    private UUID outsiderToken;
    private Long managerId;

    /** 변경 대상 셋. 전부 TEMP·ENROLLED로 시작한다 */
    private Long firstId;

    private Long secondId;
    private Long thirdId;

    @BeforeEach
    void setUp() {
        managerToken = UUID.randomUUID();
        MemberEntity manager = saveMember(managerToken, "20260101", "회원관리자");
        managerId = manager.getId();
        grant(manager, AuthorityCode.MEMBER_MANAGE);

        /*
         * MEMBER_MANAGE가 없는 회원. 권한이 아예 없는 쪽이 아니라 '다른 권한만' 가진 쪽이어야
         * 403이 인증·가입이 아니라 권한 때문이라는 것이 드러난다.
         */
        outsiderToken = UUID.randomUUID();
        grant(saveMember(outsiderToken, "20260102", "업무담당"), AuthorityCode.WORK_MANAGE);

        firstId = saveMember(UUID.randomUUID(), "20260201", "박준호").getId();
        secondId = saveMember(UUID.randomUUID(), "20260202", "이서연").getId();
        thirdId = saveMember(UUID.randomUUID(), "20260203", "최민석").getId();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM mbr_grd_hstry");
        jdbcTemplate.update("DELETE FROM mbr_stts_hstry");
        jdbcTemplate.update("DELETE FROM mbr_role_rel");
        jdbcTemplate.update(
                "DELETE FROM role_authrt_rel WHERE role_id IN"
                        + " (SELECT role_id FROM role WHERE role_nm LIKE '역할:%')");
        jdbcTemplate.update("DELETE FROM role WHERE role_nm LIKE '역할:%'");
        jdbcTemplate.update("DELETE FROM mbr");
    }

    /* ── 한 번에 셋 ──────────────────────────────────────── */

    /*
     * 세 명을 한 번에 올리면 셋 다 바뀌고 **이력이 셋 남는다.**
     *
     * 이력이 이 테스트의 중심이다 — 일괄 수정은 되돌리기 어렵고, 되돌릴 수 있게 하는 것은
     * mbr_grd_hstry에 남는 줄뿐이라 그 줄이 회원마다 하나씩 남는 것이 이 기능의 전제다(#338).
     * 변경자가 요청자인 것도 함께 본다(한 명짜리와 같은 규칙 — 본문에 변경자를 실을 자리가 없다).
     */
    @Test
    void changesThreeMembersAndRecordsThreeHistories() throws Exception {
        mockMvc.perform(
                        bulkGrade(
                                managerToken,
                                """
                                {
                                  "mbrIds": [%d, %d, %d],
                                  "aftrMbrGrdCd": "FULL",
                                  "grdAplcnYmd": "2026-09-10",
                                  "grdChgRsnCn": "정기 심사 통과"
                                }
                                """
                                        .formatted(firstId, secondId, thirdId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.totalCount").value(3))
                .andExpect(jsonPath("$.data.summary.changedCount").value(3))
                .andExpect(jsonPath("$.data.summary.skippedCount").value(0))
                .andExpect(jsonPath("$.data.summary.failedCount").value(0))
                // 순서는 요청에 실려 온 그대로다 — 화면이 자기가 보낸 목록과 나란히 놓고 읽는다
                .andExpect(jsonPath("$.data.rows[0].memberId").value(firstId))
                .andExpect(jsonPath("$.data.rows[0].name").value("박준호"))
                .andExpect(jsonPath("$.data.rows[0].status").value("CHANGED"))
                .andExpect(jsonPath("$.data.rows[0].code").doesNotExist())
                .andExpect(jsonPath("$.data.rows[0].reason").doesNotExist())
                // 등급 변경은 조직을 떠나는 전이가 아니므로 경고가 없다
                .andExpect(jsonPath("$.data.rows[0].warnings").isEmpty())
                .andExpect(jsonPath("$.data.rows[2].memberId").value(thirdId))
                .andExpect(jsonPath("$.data.rows[2].status").value("CHANGED"));

        assertThat(gradeCodeOf(firstId)).isEqualTo("FULL");
        assertThat(gradeCodeOf(secondId)).isEqualTo("FULL");
        assertThat(gradeCodeOf(thirdId)).isEqualTo("FULL");

        for (Long memberId : List.of(firstId, secondId, thirdId)) {
            assertThat(gradeHistories(memberId)).hasSize(1);
            assertThat(gradeHistories(memberId).get(0))
                    .satisfies(
                            history -> {
                                assertThat(history.getPreviousGrade().getCode()).isEqualTo("TEMP");
                                assertThat(history.getNewGrade().getCode()).isEqualTo("FULL");
                                assertThat(history.getChangeReason()).isEqualTo("정기 심사 통과");
                                assertThat(history.getChangedBy().getId()).isEqualTo(managerId);
                                assertThat(history.getAppliedDate())
                                        .isEqualTo(LocalDate.of(2026, 9, 10));
                            });
        }
    }

    /* ── 건별로 갈린다 ───────────────────────────────────── */

    /*
     * **없는 회원 id가 섞이면 그 건만 실패하고 나머지는 저장된다.**
     *
     * 하나의 트랜잭션으로 묶여 있다면 셋 다 사라진다 — 회원마다 트랜잭션이 따로라는 사실이
     * 여기서 드러난다. 실패 행에도 memberId가 실리는 것을 함께 본다(운영자가 목록에서 찾는다).
     */
    @Test
    void unknownMemberFailsAloneAndOtherMembersAreSaved() throws Exception {
        long missingId = thirdId + 9999;

        mockMvc.perform(
                        bulkGrade(
                                managerToken,
                                """
                                {"mbrIds": [%d, %d, %d], "aftrMbrGrdCd": "ASSOC"}
                                """
                                        .formatted(firstId, missingId, secondId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.totalCount").value(3))
                .andExpect(jsonPath("$.data.summary.changedCount").value(2))
                .andExpect(jsonPath("$.data.summary.failedCount").value(1))
                .andExpect(jsonPath("$.data.rows[1].memberId").value(missingId))
                .andExpect(jsonPath("$.data.rows[1].status").value("FAILED"))
                // 한 명짜리 API가 404로 내리던 바로 그 코드 문자열이다
                .andExpect(jsonPath("$.data.rows[1].code").value("NOT_FOUND"))
                // 이름을 알아낼 곳이 없으므로 null이며 빈 문자열로 채우지 않는다
                .andExpect(jsonPath("$.data.rows[1].name").doesNotExist());

        assertThat(gradeCodeOf(firstId)).isEqualTo("ASSOC");
        assertThat(gradeCodeOf(secondId)).isEqualTo("ASSOC");
        assertThat(gradeHistories(firstId)).hasSize(1);
        assertThat(gradeHistories(secondId)).hasSize(1);
    }

    /*
     * **이미 그 등급인 회원은 실패가 아니라 건너뜀이다** (#338의 판단).
     *
     * 한 명짜리 API였다면 400 NO_CHANGE인 자리이고, 그것을 실패로 세면 "3명 중 1건 실패"가 뜨는데
     * 실제로는 아무도 손볼 것이 없다. 그래서 세 번째 버킷으로 가른다 — failedCount는 0이어야 한다.
     *
     * 이력이 남지 않는 것도 함께 본다. 아무것도 바뀌지 않았으므로 남길 것이 없으며, 남으면
     * "임시회원 → 임시회원" 행이 쌓여 실제 승급 시점을 찾을 수 없게 된다.
     */
    @Test
    void memberAlreadyAtTargetGradeIsSkippedNotFailed() throws Exception {
        // 두 번째 회원만 미리 ASSOC로 올려 둔다
        mockMvc.perform(
                        bulkGrade(
                                managerToken,
                                """
                                {"mbrIds": [%d], "aftrMbrGrdCd": "ASSOC"}
                                """
                                        .formatted(secondId)))
                .andExpect(status().isOk());

        mockMvc.perform(
                        bulkGrade(
                                managerToken,
                                """
                                {"mbrIds": [%d, %d, %d], "aftrMbrGrdCd": "ASSOC"}
                                """
                                        .formatted(firstId, secondId, thirdId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.totalCount").value(3))
                .andExpect(jsonPath("$.data.summary.changedCount").value(2))
                .andExpect(jsonPath("$.data.summary.skippedCount").value(1))
                .andExpect(jsonPath("$.data.summary.failedCount").value(0))
                .andExpect(jsonPath("$.data.rows[1].memberId").value(secondId))
                .andExpect(jsonPath("$.data.rows[1].status").value("SKIPPED"))
                .andExpect(jsonPath("$.data.rows[1].code").value("NO_CHANGE"))
                // 건너뛴 행에도 이름이 실린다 — 운영자가 누구였는지 알아야 한다
                .andExpect(jsonPath("$.data.rows[1].name").value("이서연"))
                .andExpect(jsonPath("$.data.rows[1].warnings").isEmpty());

        // 건너뛴 회원의 이력은 앞선 요청이 남긴 한 줄 그대로다 (이번 요청은 아무것도 남기지 않았다)
        assertThat(gradeHistories(secondId)).hasSize(1);
        assertThat(gradeHistories(firstId)).hasSize(1);
    }

    /*
     * **한 명이 저장 도중에 터져도 앞뒤 회원은 그대로 남는다** — 이 클래스의 핵심이다.
     *
     * 없는 회원(위 테스트)은 DB에 닿기도 전에 걸리므로 "다른 회원이 되돌아가지 않는다"를 증명하지
     * 못한다. 여기서는 mbr 갱신과 이력 INSERT가 이미 끝난 뒤에 터지는 실패가 필요해서, 탈퇴 경고를
     * 모으는 자리(그 둘보다 뒤에 온다)를 한 회원에 대해서만 터뜨린다.
     *
     * 터진 회원은 상태도 이력도 남기지 않아야 한다 — 자기 트랜잭션만 통째로 되돌아갔다.
     */
    @Test
    void oneMemberFailureDoesNotRollBackOtherMembers() throws Exception {
        willAnswer(
                        invocation -> {
                            Long memberId = invocation.getArgument(0);
                            if (secondId.equals(memberId)) {
                                throw new IllegalStateException("담당 업무 조회 실패");
                            }
                            return 0L;
                        })
                .given(subWorkLoadProvider)
                .countOngoingByOwner(anyLong());

        mockMvc.perform(
                        bulkStatus(
                                managerToken,
                                """
                                {"mbrIds": [%d, %d, %d], "aftrMbrSttsCd": "WITHDRAWN"}
                                """
                                        .formatted(firstId, secondId, thirdId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.changedCount").value(2))
                .andExpect(jsonPath("$.data.summary.failedCount").value(1))
                .andExpect(jsonPath("$.data.rows[1].status").value("FAILED"))
                // 예외 메시지를 그대로 내리지 않는다 — 내부 사정이 화면으로 새어 나간다
                .andExpect(jsonPath("$.data.rows[1].reason").value("변경 도중 오류가 발생했습니다."));

        assertThat(statusCodeOf(firstId)).isEqualTo("WITHDRAWN");
        assertThat(statusCodeOf(thirdId)).isEqualTo("WITHDRAWN");
        assertThat(statusHistories(firstId)).hasSize(1);
        assertThat(statusHistories(thirdId)).hasSize(1);

        // 터진 회원은 상태도 이력도 되돌아갔다
        assertThat(statusCodeOf(secondId)).isEqualTo("ENROLLED");
        assertThat(statusHistories(secondId)).isEmpty();
    }

    /* ── 경고 ────────────────────────────────────────────── */

    /*
     * 탈퇴로 일괄 변경할 때 남은 역할·담당 업무가 **회원별 행에** 실린다.
     *
     * 한 줄로 합치지 않는 것은 합치면 그 건수가 누구 것인지 사라지기 때문이다 — 경고의 쓸모는
     * 사람이 가서 정리하는 것이다. 남은 것이 없는 회원은 건수 0짜리 줄 없이 빈 목록이다.
     */
    @Test
    void bulkWithdrawalCarriesWarningsOnEachRow() throws Exception {
        assignPlainRole(memberRepository.findById(firstId).orElseThrow(), "홍보국장");

        mockMvc.perform(
                        bulkStatus(
                                managerToken,
                                """
                                {
                                  "mbrIds": [%d, %d],
                                  "aftrMbrSttsCd": "WITHDRAWN",
                                  "sttsChgRsnCn": "졸업 정리"
                                }
                                """
                                        .formatted(firstId, secondId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.changedCount").value(2))
                .andExpect(jsonPath("$.data.rows[0].memberId").value(firstId))
                .andExpect(jsonPath("$.data.rows[0].warnings.length()").value(1))
                .andExpect(
                        jsonPath("$.data.rows[0].warnings[0].code").value("CURRENT_ROLES_REMAIN"))
                .andExpect(jsonPath("$.data.rows[0].warnings[0].count").value(1))
                // 남은 것이 없는 회원은 빈 목록이다
                .andExpect(jsonPath("$.data.rows[1].warnings").isEmpty());
    }

    /* ── 상한 · 대상 목록 ────────────────────────────────── */

    /*
     * 상한(100명)을 넘기면 400이고 **한 명도 바뀌지 않는다.**
     *
     * 요청 자체가 성립하지 않는 자리라 행별 결과가 아니라 요청 전체를 거절한다 — 이관의
     * CSV_MAPPING_INVALID가 행별 오류로 내려가지 않는 것과 같은 판단이다.
     */
    @Test
    void overTargetLimitIs400AndChangesNothing() throws Exception {
        String ids =
                LongStream.rangeClosed(1, 101)
                        .mapToObj(Long::toString)
                        .collect(Collectors.joining(", "));

        mockMvc.perform(
                        bulkGrade(
                                managerToken,
                                """
                                {"mbrIds": [%s], "aftrMbrGrdCd": "ASSOC"}
                                """
                                        .formatted(ids)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(gradeCodeOf(firstId)).isEqualTo("TEMP");
    }

    // 대상이 비어 있으면 400이다 — 아무도 고르지 않고 저장을 누른 요청이다
    @Test
    void emptyTargetsIs400() throws Exception {
        mockMvc.perform(
                        bulkGrade(
                                managerToken,
                                """
                                {"mbrIds": [], "aftrMbrGrdCd": "ASSOC"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /*
     * 같은 회원이 두 번 실려 오면 앞의 것만 남긴다.
     *
     * 접지 않으면 두 번째 호출이 NO_CHANGE로 떨어져(방금 바꾼 값이니 당연하다) 같은 사람이
     * CHANGED 한 줄과 SKIPPED 한 줄로 나뉘고, 요약의 인원 수가 실제로 손댄 인원과 갈린다.
     */
    @Test
    void duplicatedMemberIdIsFoldedIntoOneRow() throws Exception {
        mockMvc.perform(
                        bulkGrade(
                                managerToken,
                                """
                                {"mbrIds": [%d, %d], "aftrMbrGrdCd": "ASSOC"}
                                """
                                        .formatted(firstId, firstId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.totalCount").value(1))
                .andExpect(jsonPath("$.data.summary.changedCount").value(1))
                .andExpect(jsonPath("$.data.rows.length()").value(1));

        assertThat(gradeHistories(firstId)).hasSize(1);
    }

    /* ── 인가 계단 ───────────────────────────────────────── */

    // 권한은 한 명짜리와 같은 MEMBER_MANAGE다
    @Test
    void bulkChangeRequiresMemberManage() throws Exception {
        mockMvc.perform(
                        bulkGrade(
                                outsiderToken,
                                """
                                {"mbrIds": [%d], "aftrMbrGrdCd": "ASSOC"}
                                """
                                        .formatted(firstId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(gradeCodeOf(firstId)).isEqualTo("TEMP");
    }

    @Test
    void requestWithoutTokenIs401() throws Exception {
        mockMvc.perform(
                        post(GRADE_CHANGES)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"mbrIds\": [1], \"aftrMbrGrdCd\": \"ASSOC\"}"))
                .andExpect(status().isUnauthorized());
    }

    /* ── 트랜잭션 경계 ───────────────────────────────────── */

    /*
     * 일괄 서비스에 **@Transactional이 붙어 있지 않다는 사실 자체가 규칙이다** (#338).
     *
     * 붙이는 순간 안쪽 REQUIRED가 참여로 바뀌어 트랜잭션이 하나가 되고, 위의 "한 명의 실패가
     * 앞선 성공을 되돌리지 않는다" 테스트가 함께 깨진다. 그때 무엇이 잘못됐는지 곧바로 말해 주는
     * 것은 이쪽이다 — 없는 애노테이션은 코드를 읽어도 눈에 띄지 않아 "일괄인데 트랜잭션이 없네"라며
     * 붙이기 쉽다.
     */
    @Test
    void bulkChangeServiceIsNotTransactional() throws Exception {
        assertThat(MemberBulkChangeServiceImpl.class.getAnnotation(Transactional.class)).isNull();
        assertThat(
                        MemberBulkChangeServiceImpl.class
                                .getDeclaredMethod(
                                        "changeGrades",
                                        MemberBulkGradeChangeRequest.class,
                                        MemberEntity.class)
                                .getAnnotation(Transactional.class))
                .isNull();
        assertThat(
                        MemberBulkChangeServiceImpl.class
                                .getDeclaredMethod(
                                        "changeStatuses",
                                        MemberBulkStatusChangeRequest.class,
                                        MemberEntity.class)
                                .getAnnotation(Transactional.class))
                .isNull();
    }

    /* ── 헬퍼 ────────────────────────────────────────────── */

    private MockHttpServletRequestBuilder bulkGrade(UUID subject, String body) {
        return authorized(post(GRADE_CHANGES), subject)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private MockHttpServletRequestBuilder bulkStatus(UUID subject, String body) {
        return authorized(post(STATUS_CHANGES), subject)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static MockHttpServletRequestBuilder authorized(
            MockHttpServletRequestBuilder builder, UUID authUserId) {
        return builder.header("Authorization", "Bearer " + authUserId);
    }

    private String gradeCodeOf(Long memberId) {
        return memberRepository.findById(memberId).orElseThrow().getMembershipGrade().getCode();
    }

    private String statusCodeOf(Long memberId) {
        return memberRepository.findById(memberId).orElseThrow().getMembershipStatus().getCode();
    }

    private List<MemberGradeHistoryEntity> gradeHistories(Long memberId) {
        return memberGradeHistoryRepository.findByMemberIdOrderByCreatedAtDescIdDesc(
                memberId, PageRequest.of(0, 10));
    }

    private List<MemberStatusHistoryEntity> statusHistories(Long memberId) {
        return memberStatusHistoryRepository.findByMemberIdOrderByCreatedAtDescIdDesc(
                memberId, PageRequest.of(0, 10));
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

    private void grant(MemberEntity member, AuthorityCode authority) {
        AuthorityFixture.grant(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                authorityRepository,
                roleAuthorityRelationRepository,
                member,
                authority);
    }

    /*
     * 권한이 붙지 않은 역할. 여기서 보고 싶은 것은 인가가 아니라 '남아 있는 역할의 건수'다.
     * 이름에 '역할:' 접두사를 붙이는 것은 뒷정리가 이 클래스가 만든 역할만 지우기 때문이다.
     */
    private void assignPlainRole(MemberEntity member, String roleName) {
        MemberRoleClassificationEntity position =
                memberRoleClassificationRepository.findById("POSITION").orElseThrow();
        MemberRoleEntity role =
                memberRoleRepository.save(MemberRoleEntity.create(99, "역할:" + roleName, position));
        memberRoleAssignmentRepository.save(
                MemberRoleAssignmentEntity.create(
                        member, role, LocalDate.now().minusYears(1), true));
    }
}
