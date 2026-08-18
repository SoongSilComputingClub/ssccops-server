package org.sscc.ssccopsserver.domain.operation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeHistoryRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleClassificationRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusHistoryRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.domain.member.service.AuthorityPolicy;
import org.sscc.ssccopsserver.domain.member.service.MemberInitialHistoryRecorder;
import org.sscc.ssccopsserver.domain.member.service.MemberLinkAttemptLimiter;
import org.sscc.ssccopsserver.domain.member.service.MemberService;
import org.sscc.ssccopsserver.domain.member.service.MemberServiceImpl;
import org.sscc.ssccopsserver.domain.operation.dto.ApprovalInboxItemResponse;
import org.sscc.ssccopsserver.domain.operation.dto.DashboardResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkChecklistItemUpdateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkSummaryResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkTransitionRequest;
import org.sscc.ssccopsserver.domain.operation.dto.WorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkChecklistItemEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.TransitionAction;
import org.sscc.ssccopsserver.domain.operation.entity.WorkType;
import org.sscc.ssccopsserver.domain.operation.repository.OperationRepository;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkApprovalRepository;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkApprovalVoteRepository;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkChecklistItemRepository;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkRejectionRepository;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkRepository;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkStatusHistoryRepository;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkTypeRepository;
import org.sscc.ssccopsserver.domain.operation.repository.WorkRepository;
import org.sscc.ssccopsserver.global.config.ClockConfig;
import org.sscc.ssccopsserver.global.config.JpaAuditingConfig;
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.MemberRoleFixture;
import org.sscc.ssccopsserver.support.SubWorkTypeFixture;

/*
 * 운영 대시보드 (OPS-038 · ssccops-web#60).
 *
 * 세 영역이 각자의 조회 서비스(승인함 · 하위 업무 목록)와 같은 규칙을 쓰는지만 확인한다 —
 * 필터·정렬 자체는 ApprovalServiceImplTest·SubWorkServiceImplSearchTest가 이미 못 박아 뒀다.
 */
@DataJpaTest
@Import({JpaAuditingConfig.class, AuthorityPolicy.class, ClockConfig.class})
@ActiveProfiles("test")
class DashboardServiceImplTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 8, 20, 12, 0, 0, 0, KST);
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW.toInstant(), KST);

    // 마감일은 오늘인데 마감 시각은 이미 지난 건 (#121)
    private static final OffsetDateTime DUE_TODAY = NOW.minusHours(3);

    @Autowired private OperationRepository operationRepository;
    @Autowired private WorkRepository workRepository;
    @Autowired private SubWorkRepository subWorkRepository;
    @Autowired private SubWorkTypeRepository subWorkTypeRepository;
    @Autowired private SubWorkChecklistItemRepository subWorkChecklistItemRepository;
    @Autowired private SubWorkStatusHistoryRepository subWorkStatusHistoryRepository;
    @Autowired private SubWorkApprovalRepository subWorkApprovalRepository;
    @Autowired private SubWorkApprovalVoteRepository subWorkApprovalVoteRepository;
    @Autowired private SubWorkRejectionRepository subWorkRejectionRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberGradeHistoryRepository memberGradeHistoryRepository;
    @Autowired private MemberStatusHistoryRepository memberStatusHistoryRepository;
    @Autowired private AuthorityPolicy authorityPolicy;
    @Autowired private TestEntityManager entityManager;

    private SubWorkService subWorkService;
    private DashboardService dashboardService;

    private MemberEntity registrant;
    private MemberEntity viewer;
    private MemberEntity otherOwner;
    private Long parentWorkId;
    private long approvalFreeTypeId;
    private long expenditureTypeId;

    @BeforeEach
    void setUp() {
        MemberService memberService =
                new MemberServiceImpl(
                        memberRepository,
                        memberRoleRepository,
                        memberRoleAssignmentRepository,
                        memberGradeRepository,
                        memberStatusRepository,
                        memberGradeHistoryRepository,
                        memberStatusHistoryRepository,
                        new MemberInitialHistoryRecorder(
                                memberGradeHistoryRepository, memberStatusHistoryRepository),
                        authorityPolicy,
                        new MemberLinkAttemptLimiter(FIXED_CLOCK),
                        FIXED_CLOCK);
        ApprovalAuthorityPolicy approvalAuthorityPolicy =
                new ApprovalAuthorityPolicy(memberService);
        subWorkService =
                new SubWorkServiceImpl(
                        operationRepository,
                        workRepository,
                        subWorkRepository,
                        subWorkTypeRepository,
                        subWorkChecklistItemRepository,
                        subWorkStatusHistoryRepository,
                        subWorkApprovalRepository,
                        subWorkApprovalVoteRepository,
                        subWorkRejectionRepository,
                        memberService,
                        approvalAuthorityPolicy,
                        new SubWorkOwnershipPolicy(authorityPolicy),
                        new DeadlinePolicy(FIXED_CLOCK),
                        FIXED_CLOCK,
                        entityManager.getEntityManager());
        ApprovalService approvalService =
                new ApprovalServiceImpl(
                        subWorkRepository,
                        subWorkChecklistItemRepository,
                        subWorkStatusHistoryRepository,
                        subWorkApprovalVoteRepository,
                        subWorkRejectionRepository,
                        approvalAuthorityPolicy,
                        FIXED_CLOCK);
        dashboardService =
                new DashboardServiceImpl(approvalService, subWorkService, authorityPolicy);

        registrant = saveMember("20200001", "김도현");
        MemberRoleFixture.assign(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                registrant,
                MemberRoleFixture.TREASURER);
        viewer = saveMember("20200002", "이서연");
        /*
         * 컨트롤러 인가는 WORK_READ뿐이라(#101) viewer가 국원이어도 대시보드에 들어올 수
         * 있지만, 이 테스트 파일의 pendingApproval 검증들은 원래 "승인함과 같은 데이터가
         * 그대로 온다"만 확인하던 것이라 WORK_MANAGE를 쥔 국장으로 둔다 — WORK_MANAGE 없는
         * 조회자의 승인 대기가 가려지는 것은 별도 테스트(pendingApprovalIsHiddenWithoutWorkManage)가 본다.
         */
        MemberRoleFixture.assign(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                viewer,
                MemberRoleFixture.DIRECTOR);
        otherOwner = saveMember("20200003", "박현우");

        approvalFreeTypeId =
                SubWorkTypeFixture.idOf(subWorkTypeRepository, SubWorkTypeFixture.APPROVAL_FREE);
        expenditureTypeId =
                SubWorkTypeFixture.idOf(subWorkTypeRepository, SubWorkTypeFixture.EXPENDITURE);

        WorkService workService =
                new WorkServiceImpl(
                        operationRepository,
                        workRepository,
                        subWorkRepository,
                        subWorkChecklistItemRepository,
                        memberService,
                        entityManager.getEntityManager());
        parentWorkId =
                workService
                        .createWork(
                                new WorkCreateRequest(
                                        "2026 하반기 MT",
                                        WorkType.EVENT,
                                        registrant.getId(),
                                        null,
                                        null,
                                        null,
                                        null),
                                registrant)
                        .workId();
    }

    // 내 업무 목록은 담당자가 나인 건만 담는다 — 다른 담당자의 하위 업무는 섞이지 않는다
    @Test
    void myTasksOnlyIncludesSubWorksOwnedByViewer() {
        Long mine = createSubWork("내 업무", viewer.getId(), approvalFreeTypeId, NOW.plusDays(1));
        createSubWork("남의 업무", otherOwner.getId(), approvalFreeTypeId, NOW.plusDays(1));

        DashboardResponse dashboard = dashboardService.getDashboard(viewer);

        assertThat(dashboard.myTasks())
                .extracting(SubWorkSummaryResponse::subWorkId)
                .containsExactly(mine);
    }

    // '전체' 필터는 화면 몫이다 — 서버는 완료 건도 그대로 싣는다
    @Test
    void myTasksIncludesCompletedSubWorks() {
        Long done = createSubWork("완료된 내 업무", viewer.getId(), approvalFreeTypeId, NOW.plusDays(1));
        complete(done);

        DashboardResponse dashboard = dashboardService.getDashboard(viewer);

        assertThat(dashboard.myTasks())
                .extracting(SubWorkSummaryResponse::subWorkId)
                .containsExactly(done);
    }

    /*
     * 다가오는 마감은 조회 시점 기준 ±5일 범위에 든 건만 담고, 이미 끝난 건은 뺀다
     * (이슈#60). 범위 밖(6일 이상 이르거나 늦은 건)은 담지 않는다.
     */
    @Test
    void upcomingDeadlinesKeepsOnlyWithinFiveDayWindowExcludingDone() {
        createSubWork("6일 전 마감(범위 밖)", viewer.getId(), approvalFreeTypeId, NOW.minusDays(6));
        Long nearPast =
                createSubWork("3일 전 마감", viewer.getId(), approvalFreeTypeId, NOW.minusDays(3));
        Long nearFuture =
                createSubWork("3일 뒤 마감", viewer.getId(), approvalFreeTypeId, NOW.plusDays(3));
        createSubWork("6일 뒤 마감(범위 밖)", viewer.getId(), approvalFreeTypeId, NOW.plusDays(6));
        Long doneWithinWindow =
                createSubWork("범위 안이지만 완료된 건", viewer.getId(), approvalFreeTypeId, NOW.plusDays(1));
        complete(doneWithinWindow);

        DashboardResponse dashboard = dashboardService.getDashboard(viewer);

        assertThat(dashboard.upcomingDeadlines())
                .extracting(SubWorkSummaryResponse::subWorkId)
                .containsExactly(nearPast, nearFuture);
    }

    /*
     * 마감일이 오늘인 건은 두 영역 어디에서도 지연이 아니다 (#121).
     *
     * 원 결함이 드러난 자리가 정확히 여기다 — '다가오는 마감'은 화면이 마감 일자로 D-day를
     * 세어 'D-DAY'를 그리는데 '내 업무 목록'은 서버가 내린 isDelayed로 '지연' 배지를 붙여,
     * 같은 하위 업무가 한 화면에서 두 가지로 보였다. 두 영역이 같은 판정을 쓰는지 못 박는다.
     */
    @Test
    void subWorkDueTodayIsNotDelayedInEitherDashboardSection() {
        Long dueToday = createSubWork("오늘 아침 마감", viewer.getId(), approvalFreeTypeId, DUE_TODAY);

        DashboardResponse dashboard = dashboardService.getDashboard(viewer);

        assertThat(dashboard.myTasks())
                .extracting(SubWorkSummaryResponse::subWorkId, SubWorkSummaryResponse::isDelayed)
                .containsExactly(tuple(dueToday, false));
        assertThat(dashboard.upcomingDeadlines())
                .extracting(SubWorkSummaryResponse::subWorkId, SubWorkSummaryResponse::isDelayed)
                .containsExactly(tuple(dueToday, false));
    }

    // 하루만 지나도 두 영역 모두 지연이다 — 경계는 마감일 다음 날 0시다
    @Test
    void subWorkDueYesterdayIsDelayedInEitherDashboardSection() {
        Long dueYesterday =
                createSubWork("어제 마감", viewer.getId(), approvalFreeTypeId, NOW.minusDays(1));

        DashboardResponse dashboard = dashboardService.getDashboard(viewer);

        assertThat(dashboard.myTasks())
                .extracting(SubWorkSummaryResponse::subWorkId, SubWorkSummaryResponse::isDelayed)
                .containsExactly(tuple(dueYesterday, true));
        assertThat(dashboard.upcomingDeadlines())
                .extracting(SubWorkSummaryResponse::subWorkId, SubWorkSummaryResponse::isDelayed)
                .containsExactly(tuple(dueYesterday, true));
    }

    // 승인 대기는 승인함(OPS-017)의 대기 탭을 그대로 재사용한다 — 아직 검토에 오르지 않은 건은 빠진다
    @Test
    void pendingApprovalReusesApprovalInboxPendingTab() {
        Long notYetRequested =
                createSubWork(
                        "아직 올리지 않은 건", registrant.getId(), expenditureTypeId, NOW.plusDays(1));
        Long inReview = subWorkInReview("검토 중인 지출 건", NOW.plusDays(2));

        DashboardResponse dashboard = dashboardService.getDashboard(viewer);

        assertThat(dashboard.pendingApproval())
                .extracting(ApprovalInboxItemResponse::subWorkId)
                .containsExactly(inReview)
                .doesNotContain(notYetRequested);
    }

    // 카드 미리보기는 승인함 정렬(마감 오름차순)에서 앞쪽 5건만 싣는다 — 나머지는 '전체보기'의 몫이다
    @Test
    void pendingApprovalPreviewCapsAtFiveInDueAtOrder() {
        List<Long> ids =
                List.of(
                        subWorkInReview("건 1", NOW.plusDays(1)),
                        subWorkInReview("건 2", NOW.plusDays(2)),
                        subWorkInReview("건 3", NOW.plusDays(3)),
                        subWorkInReview("건 4", NOW.plusDays(4)),
                        subWorkInReview("건 5", NOW.plusDays(5)),
                        subWorkInReview("건 6", NOW.plusDays(6)));

        DashboardResponse dashboard = dashboardService.getDashboard(viewer);

        assertThat(dashboard.pendingApproval()).hasSize(5);
        assertThat(dashboard.pendingApproval())
                .extracting(ApprovalInboxItemResponse::subWorkId)
                .containsExactlyElementsOf(ids.subList(0, 5));
    }

    /*
     * WORK_READ만 가진 조회자(국원)는 대시보드에 들어올 수 있지만(#101), 승인 대기는
     * 승인함(WORK_MANAGE)과 같은 데이터라 그 영역만 빈 배열로 온다. 내 업무는 원래도
     * 본인 스코프라 그대로 채워지고, 다가오는 마감은 전체가 아니라 본인이 담당자인
     * 것만 좁혀서 온다 — 남의 마감(검토 중인 지출 건, registrant 담당)은 빠진다.
     */
    @Test
    void pendingApprovalIsHiddenAndUpcomingDeadlinesAreScopedWithoutWorkManage() {
        MemberEntity staffMember = saveMember("20200099", "국원회원");
        MemberRoleFixture.assign(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                staffMember,
                "국원");
        Long mine = createSubWork("내 업무", staffMember.getId(), approvalFreeTypeId, NOW.plusDays(1));
        subWorkInReview("검토 중인 지출 건", NOW.plusDays(2));

        DashboardResponse dashboard = dashboardService.getDashboard(staffMember);

        assertThat(dashboard.pendingApproval()).isEmpty();
        assertThat(dashboard.myTasks())
                .extracting(SubWorkSummaryResponse::subWorkId)
                .containsExactly(mine);
        assertThat(dashboard.upcomingDeadlines())
                .extracting(SubWorkSummaryResponse::subWorkId)
                .containsExactly(mine);
    }

    private MemberEntity saveMember(String studentNumber, String name) {
        return MemberFixture.save(
                memberRepository,
                memberGradeRepository,
                memberStatusRepository,
                UUID.randomUUID(),
                studentNumber,
                name,
                studentNumber + "@sscc.org");
    }

    private Long createSubWork(
            String title, Long ownerId, long subWorkTypeId, OffsetDateTime dueAt) {
        entityManager.flush();
        entityManager.clear();
        return subWorkService
                .createSubWork(
                        new SubWorkCreateRequest(
                                parentWorkId,
                                title,
                                subWorkTypeId,
                                ownerId,
                                null,
                                null,
                                dueAt,
                                null,
                                null,
                                null),
                        registrant)
                .subWorkId();
    }

    private Long subWorkInReview(String title, OffsetDateTime dueAt) {
        Long subWorkId = createSubWork(title, registrant.getId(), expenditureTypeId, dueAt);
        transition(subWorkId, TransitionAction.START);
        transition(subWorkId, TransitionAction.REQUEST_REVIEW);
        return subWorkId;
    }

    // 정상 경로로 완료까지 올린다 (TR-01 → TR-02 → 체크리스트 충족 → TR-03)
    private void complete(Long subWorkId) {
        transition(subWorkId, TransitionAction.START);
        transition(subWorkId, TransitionAction.REQUEST_REVIEW);
        for (Long itemId : checklistItemIds(subWorkId)) {
            entityManager.flush();
            entityManager.clear();
            subWorkService.updateChecklistItem(
                    subWorkId, itemId, new SubWorkChecklistItemUpdateRequest(true), registrant);
        }
        transition(subWorkId, TransitionAction.APPROVE_COMPLETE);
    }

    private void transition(Long subWorkId, TransitionAction action) {
        entityManager.flush();
        entityManager.clear();
        subWorkService.transitionSubWork(
                subWorkId, new SubWorkTransitionRequest(action, null), registrant);
    }

    private List<Long> checklistItemIds(Long subWorkId) {
        entityManager.flush();
        entityManager.clear();
        SubWorkEntity subWork = subWorkRepository.findById(subWorkId).orElseThrow();
        return subWorkChecklistItemRepository.findBySubWorkOrderBySortOrderAsc(subWork).stream()
                .map(SubWorkChecklistItemEntity::getId)
                .toList();
    }
}
