package org.sscc.ssccopsserver.domain.operation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import org.sscc.ssccopsserver.domain.member.service.MemberService;
import org.sscc.ssccopsserver.domain.member.service.MemberServiceImpl;
import org.sscc.ssccopsserver.domain.operation.dto.ApprovalInboxItemResponse;
import org.sscc.ssccopsserver.domain.operation.dto.ApprovalInboxResponse;
import org.sscc.ssccopsserver.domain.operation.dto.ApprovalInboxSearchCondition;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkChecklistItemUpdateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkTransitionRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkVoteRequest;
import org.sscc.ssccopsserver.domain.operation.dto.WorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.entity.ApprovalStatus;
import org.sscc.ssccopsserver.domain.operation.entity.OperationPriority;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkChecklistItemEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkTypeEntity;
import org.sscc.ssccopsserver.domain.operation.entity.TransitionAction;
import org.sscc.ssccopsserver.domain.operation.entity.VoteChoice;
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
import org.sscc.ssccopsserver.global.apipayload.code.error.CommonErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;
import org.sscc.ssccopsserver.global.config.ClockConfig;
import org.sscc.ssccopsserver.global.config.JpaAuditingConfig;
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.MemberRoleFixture;
import org.sscc.ssccopsserver.support.SubWorkTypeFixture;

/*
 * 승인함 조회 (OPS-017 · #47).
 *
 * 승인함이 목록 화면(OPS-008)과 다른 점을 고정한다 — 탭이 승인 상태로 갈리고, 대기 탭은
 * 검토 단계의 건만 담으며, 카드가 정족수 진행·체크리스트·내 표까지 함께 싣는다.
 */
@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
// AuthorityPolicy는 @Service라 @DataJpaTest 슬라이스에 없다. MemberServiceImpl이 프로필의
// capabilities를 계산하는 데 쓰므로(#9) 정책과 그 Clock만 슬라이스에 들여온다.
@Import({JpaAuditingConfig.class, AuthorityPolicy.class, ClockConfig.class})
@ActiveProfiles("test")
class ApprovalServiceImplTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final OffsetDateTime START = OffsetDateTime.of(2026, 9, 1, 18, 0, 0, 0, KST);
    private static final OffsetDateTime END = START.plusHours(2);
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 8, 20, 12, 0, 0, 0, KST);
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW.toInstant(), KST);

    private static final int REQUIRED_AGREE_COUNT = 2;

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
    private ApprovalService approvalService;

    private MemberEntity registrant;
    private MemberEntity president;
    private MemberEntity staff;

    private Long parentWorkId;
    private Long quorumTypeId;
    private Long approvalFreeTypeId;

    @BeforeEach
    void setUp() {
        MemberService memberService =
                new MemberServiceImpl(
                        memberRepository,
                        memberRoleAssignmentRepository,
                        memberGradeRepository,
                        memberStatusRepository,
                        memberGradeHistoryRepository,
                        memberStatusHistoryRepository,
                        authorityPolicy,
                        FIXED_CLOCK);
        WorkService workService =
                new WorkServiceImpl(
                        operationRepository,
                        workRepository,
                        subWorkRepository,
                        subWorkChecklistItemRepository,
                        memberService,
                        entityManager.getEntityManager());
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
                        new ApprovalAuthorityPolicy(memberService),
                        FIXED_CLOCK,
                        entityManager.getEntityManager());
        approvalService =
                new ApprovalServiceImpl(
                        subWorkRepository,
                        subWorkChecklistItemRepository,
                        subWorkStatusHistoryRepository,
                        subWorkApprovalVoteRepository,
                        subWorkRejectionRepository,
                        new ApprovalAuthorityPolicy(memberService),
                        FIXED_CLOCK);

        registrant = saveMember("20200001", "김도현", null);
        president = saveMember("20200002", "백승우", MemberRoleFixture.PRESIDENT);
        staff = saveMember("20200003", "박지훈", MemberRoleFixture.PLANNING_STAFF);

        SubWorkTypeEntity quorumType =
                SubWorkTypeFixture.entityOf(subWorkTypeRepository, SubWorkTypeFixture.ANNOUNCEMENT);
        quorumType.update(
                quorumType.getTypeName(),
                true,
                "PRESIDENT",
                true,
                REQUIRED_AGREE_COUNT,
                List.of("공지 문안 검수", "게시 채널 확정"));
        quorumTypeId = quorumType.getId();
        approvalFreeTypeId =
                SubWorkTypeFixture.idOf(subWorkTypeRepository, SubWorkTypeFixture.APPROVAL_FREE);

        parentWorkId =
                workService
                        .createWork(
                                new WorkCreateRequest(
                                        "2026 신입 모집",
                                        WorkType.EVENT,
                                        registrant.getId(),
                                        START,
                                        END,
                                        null,
                                        null),
                                registrant)
                        .workId();
    }

    /*
     * 대기 탭은 검토 단계의 건만 담는다. 승인이 필요한 하위 업무는 등록 시점부터 승인 상태가
     * 대기라, 승인 상태만 보면 아직 올라오지도 않은 건이 승인함에 뜬다.
     */
    @Test
    void pendingTabHoldsOnlySubWorksInReview() {
        Long notYetRequested = createSubWork(quorumTypeId, "아직 올리지 않은 건");
        Long inReview = subWorkInReview(quorumTypeId, "검토 중인 건");

        List<ApprovalInboxItemResponse> approvals = search(null).approvals();

        assertThat(approvals)
                .extracting(ApprovalInboxItemResponse::subWorkId)
                .containsExactly(inReview);
        assertThat(approvals)
                .extracting(ApprovalInboxItemResponse::subWorkId)
                .doesNotContain(notYetRequested);
    }

    // 승인 불필요 유형은 승인 절차를 아예 타지 않으므로 어느 탭에도 없다
    @Test
    void approvalFreeTypeNeverAppears() {
        subWorkInReview(approvalFreeTypeId, "내부 행사 준비");

        assertThat(search(null).approvals()).isEmpty();
        assertThat(search("APPROVED").approvals()).isEmpty();
        assertThat(search("REJECTED").approvals()).isEmpty();
    }

    // 반려 후 다시 올라온 건도 승인자가 처리해야 할 건이라 대기 탭에 함께 담긴다
    @Test
    void pendingTabIncludesReapprovalRequired() {
        Long resubmitted = subWorkInReview(quorumTypeId, "반려 후 다시 올린 건");
        transition(resubmitted, TransitionAction.REJECT, "문안 재검토 필요", president);
        transition(resubmitted, TransitionAction.REQUEST_REVIEW, null, registrant);

        List<ApprovalInboxItemResponse> approvals = search(null).approvals();

        assertThat(approvals).hasSize(1);
        assertThat(approvals.get(0).approvalStatus()).isEqualTo(ApprovalStatus.REAPPROVAL_REQUIRED);
    }

    // 승인·반려 탭은 처리가 끝난 건을 본다. 대기 탭과 겹치지 않는다
    @Test
    void approvedAndRejectedTabsSeeProcessedSubWorks() {
        Long approved = subWorkInReview(quorumTypeId, "승인된 건");
        completeChecklist(approved);
        vote(approved, VoteChoice.AGREE, staff);
        vote(approved, VoteChoice.AGREE, president);
        transition(approved, TransitionAction.APPROVE_COMPLETE, null, president);

        Long rejected = subWorkInReview(quorumTypeId, "반려된 건");
        transition(rejected, TransitionAction.REJECT, "예산 근거 부족", president);

        assertThat(search("APPROVED").approvals())
                .extracting(ApprovalInboxItemResponse::subWorkId)
                .containsExactly(approved);
        assertThat(search("REJECTED").approvals())
                .extracting(ApprovalInboxItemResponse::subWorkId)
                .containsExactly(rejected);
        assertThat(search(null).approvals()).isEmpty();
    }

    // 카드 한 장이 그리는 값이 모두 실린다 — 상태·유형·승인자·요청자·정족수·체크리스트
    @Test
    void cardCarriesEveryValueTheScreenDraws() {
        Long subWorkId = subWorkInReview(quorumTypeId, "9월 신입 모집 포스터");
        checkFirstChecklistItem(subWorkId);
        vote(subWorkId, VoteChoice.AGREE, staff);

        ApprovalInboxItemResponse card = search(null).approvals().get(0);

        assertThat(card.title()).isEqualTo("9월 신입 모집 포스터");
        assertThat(card.approvalStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(card.subWorkTypeName()).isEqualTo(SubWorkTypeFixture.ANNOUNCEMENT);
        assertThat(card.authorizerRoleCode()).isEqualTo("PRESIDENT");
        assertThat(card.registrantName()).isEqualTo("김도현");
        // 등록 일시가 아니라 검토요청 시각이다
        assertThat(card.requestedAt()).isEqualTo(NOW);
        assertThat(card.quorum().needed()).isTrue();
        assertThat(card.quorum().requiredCount()).isEqualTo(REQUIRED_AGREE_COUNT);
        assertThat(card.quorum().currentCount()).isEqualTo(1);
        assertThat(card.quorum().met()).isFalse();
        assertThat(card.checklistSummary().completedCount()).isEqualTo(1);
        assertThat(card.checklistSummary().totalCount()).isEqualTo(2);
    }

    /*
     * 반려 카드는 사유를 함께 싣는다 (#62). 없으면 카드가 '반려됨'만 말하고 끝나, 반려 사유를
     * 요청자에게 전달하겠다는 약속(반려 모달)이 화면 어디에서도 지켜지지 않는다.
     */
    @Test
    void rejectedCardCarriesTheRejectionReason() {
        Long subWorkId = subWorkInReview(quorumTypeId, "7월 월간 활동보고서");
        transition(subWorkId, TransitionAction.REJECT, "부서별 활동 누락", president);

        ApprovalInboxItemResponse card = search("REJECTED").approvals().get(0);

        assertThat(card.approvalStatus()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(card.latestRejectionReason()).isEqualTo("부서별 활동 누락");
    }

    /*
     * 반려 탭에서만 채우지 않는다. 반려 후 다시 올라온 건은 대기 탭에 있고, 승인자가 '무엇이
     * 걸려서 되돌아왔던 건인지'를 알아야 하는 자리가 바로 거기다.
     */
    @Test
    void reapprovalRequiredCardKeepsTheLastRejectionReason() {
        Long subWorkId = subWorkInReview(quorumTypeId, "반려 후 다시 올린 건");
        transition(subWorkId, TransitionAction.REJECT, "문안 재검토 필요", president);
        transition(subWorkId, TransitionAction.REQUEST_REVIEW, null, registrant);

        ApprovalInboxItemResponse card = search(null).approvals().get(0);

        assertThat(card.approvalStatus()).isEqualTo(ApprovalStatus.REAPPROVAL_REQUIRED);
        assertThat(card.latestRejectionReason()).isEqualTo("문안 재검토 필요");
    }

    /*
     * 여러 번 반려된 건은 직전 사유만 싣는다. 고정 시계라 두 반려의 일시가 같은데, 그때
     * 식별자로 동률을 끊지 않으면 어느 사유가 실릴지 실행할 때마다 달라진다.
     */
    @Test
    void repeatedlyRejectedCardCarriesOnlyTheMostRecentReason() {
        Long subWorkId = subWorkInReview(quorumTypeId, "7월 월간 활동보고서");
        transition(subWorkId, TransitionAction.REJECT, "부서별 활동 누락", president);
        transition(subWorkId, TransitionAction.REQUEST_REVIEW, null, registrant);
        transition(subWorkId, TransitionAction.REJECT, "예산 집행 내역 불일치", president);

        assertThat(search("REJECTED").approvals().get(0).latestRejectionReason())
                .isEqualTo("예산 집행 내역 불일치");
    }

    // 반려된 적이 없으면 NULL이다 — 화면이 사유 줄 자체를 그리지 않는다
    @Test
    void neverRejectedCardHasNoReason() {
        subWorkInReview(quorumTypeId, "9월 신입 모집 포스터");

        assertThat(search(null).approvals().get(0).latestRejectionReason()).isNull();
    }

    /*
     * 카드가 승인·반려 버튼을 그릴지 정하는 값 (#62). 유형이 지정한 승인자 역할(PRESIDENT)을
     * 가진 사람만 true다 — 없으면 화면은 운영진 전원에게 버튼을 그리고, 승인자가 아닌 사람은
     * 누른 뒤에야 403을 본다.
     */
    @Test
    void cardTellsWhetherTheViewerMayDecide() {
        subWorkInReview(quorumTypeId, "9월 신입 모집 포스터");

        ApprovalInboxItemResponse forPresident = search(null, president).approvals().get(0);
        ApprovalInboxItemResponse forStaff = search(null, staff).approvals().get(0);

        assertThat(forPresident.canApprove()).isTrue();
        assertThat(forPresident.canReject()).isTrue();
        // 투표는 할 수 있지만 최종 승인·반려는 승인자 역할의 몫이다
        assertThat(forStaff.canApprove()).isFalse();
        assertThat(forStaff.canReject()).isFalse();
    }

    /*
     * 권한 판정은 상세(OPS-009)와 같은 정책 하나에서 나와야 한다. 두 벌이 되면 승인함에는
     * 버튼이 보이는데 상세에서는 사라지거나, 눌렀을 때 403이 나는 상태가 된다.
     */
    @Test
    void cardAuthorityMatchesTheDetailScreen() {
        Long subWorkId = subWorkInReview(quorumTypeId, "9월 신입 모집 포스터");

        for (MemberEntity viewer : List.of(president, staff, registrant)) {
            ApprovalInboxItemResponse card = search(null, viewer).approvals().get(0);
            entityManager.flush();
            entityManager.clear();
            assertThat(card.canApprove())
                    .isEqualTo(subWorkService.getSubWork(subWorkId, viewer).canApprove());
        }
    }

    /*
     * 파생 값은 전부 목록 전체를 한 번에 집계해 붙인다 (DB-13). 카드마다 조회하면 페이지가
     * 커질수록 쿼리가 함께 늘어나는데, 그 회귀는 응답 내용이 같아 테스트로 못 박지 않으면
     * 드러나지 않는다.
     */
    @Test
    void searchRunsNineQueriesRegardlessOfRowCount() {
        for (int index = 0; index < 5; index++) {
            /*
             * 카드마다 요청자가 다르고, 그 요청자가 담당자와도 다르다. 등록자와 담당자가 같으면
             * 담당자 연관이 이미 끌어온 회원을 등록자가 재사용해 N+1이 가려진다.
             */
            MemberEntity otherRegistrant = saveMember("2021000" + index, "요청자 " + index, null);
            Long subWorkId = subWorkInReview(quorumTypeId, "검토 중 " + index, otherRegistrant);
            transition(subWorkId, TransitionAction.REJECT, "보완 필요 " + index, president);
            transition(subWorkId, TransitionAction.REQUEST_REVIEW, null, otherRegistrant);
        }
        entityManager.flush();
        entityManager.clear();

        Statistics statistics =
                entityManager
                        .getEntityManager()
                        .getEntityManagerFactory()
                        .unwrap(SessionFactory.class)
                        .getStatistics();
        statistics.clear();

        ApprovalInboxResponse response =
                approvalService.searchApprovals(
                        new ApprovalInboxSearchCondition(null, null, null), president);

        assertThat(response.approvals()).hasSize(5);
        assertThat(response.approvals())
                .extracting(ApprovalInboxItemResponse::latestRejectionReason)
                .doesNotContainNull();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(9);
    }

    /*
     * 단독 유형은 진행바를 그리지 않는다. 0으로 채우면 '정족수가 있는데 아무도 찬성하지 않은
     * 상태'와 구분되지 않으므로 NULL로 둔다.
     */
    @Test
    void soleDecisionCardHasNoQuorumProgress() {
        Long soleTypeId =
                SubWorkTypeFixture.idOf(subWorkTypeRepository, SubWorkTypeFixture.EXPENDITURE);
        subWorkInReview(soleTypeId, "봄MT 대관료 집행");

        ApprovalInboxItemResponse card = search(null).approvals().get(0);

        assertThat(card.quorum().needed()).isFalse();
        assertThat(card.quorum().requiredCount()).isNull();
        assertThat(card.quorum().currentCount()).isNull();
        assertThat(card.quorum().met()).isNull();
    }

    // 내가 던진 표가 실려야 화면이 버튼 선택 상태를 그린다. 남의 표는 실리지 않는다
    @Test
    void cardCarriesMyOwnVoteOnly() {
        Long subWorkId = subWorkInReview(quorumTypeId, "9월 신입 모집 포스터");
        vote(subWorkId, VoteChoice.DISAGREE, staff);

        assertThat(search(null, staff).approvals().get(0).myVote()).isEqualTo(VoteChoice.DISAGREE);
        assertThat(search(null, president).approvals().get(0).myVote()).isNull();
    }

    /*
     * 반려 후 다시 올라오면 회차가 바뀐다. 이전 회차의 찬성도, 그때 내가 던진 표도
     * 이번 회차의 값이 아니다.
     */
    @Test
    void cardShowsOnlyCurrentRoundVotes() {
        Long subWorkId = subWorkInReview(quorumTypeId, "9월 신입 모집 포스터");
        vote(subWorkId, VoteChoice.AGREE, staff);
        vote(subWorkId, VoteChoice.AGREE, president);
        transition(subWorkId, TransitionAction.REJECT, "문안 재검토 필요", president);
        transition(subWorkId, TransitionAction.REQUEST_REVIEW, null, registrant);

        ApprovalInboxItemResponse card = search(null, staff).approvals().get(0);

        assertThat(card.quorum().currentCount()).isZero();
        assertThat(card.quorum().met()).isFalse();
        assertThat(card.myVote()).isNull();
    }

    // 소프트 삭제된 건은 승인함에도 없다 (AGG-03)
    @Test
    void softDeletedSubWorkIsExcluded() {
        Long subWorkId = subWorkInReview(quorumTypeId, "삭제된 건");
        entityManager.flush();
        entityManager.clear();
        SubWorkEntity subWork = subWorkRepository.findById(subWorkId).orElseThrow();
        subWork.getOperation().softDelete(NOW.toInstant());

        assertThat(search(null).approvals()).isEmpty();
    }

    // 목록이므로 페이지 봉투가 함께 나가고, 걸러진 건수는 이번 탭 기준이다
    @Test
    void pageEnvelopeCountsCurrentTab() {
        subWorkInReview(quorumTypeId, "검토 중 1");
        subWorkInReview(quorumTypeId, "검토 중 2");
        createSubWork(quorumTypeId, "아직 올리지 않은 건");

        ApprovalInboxResponse response = search(null);

        assertThat(response.approvals()).hasSize(2);
        assertThat(response.page().totalCount()).isEqualTo(2);
        assertThat(response.page().hasNext()).isFalse();
        assertThat(response.page().sort()).isEqualTo("dueAt");
    }

    // 탭 값이 기준 코드에 없으면 조용히 기본값으로 떨어뜨리지 않는다 (VL-09)
    @Test
    void unknownTabIsRejected() {
        assertThatThrownBy(() -> search("DONE"))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(CommonErrorCode.INVALID_CODE_VALUE);
    }

    private ApprovalInboxResponse search(String status) {
        return search(status, president);
    }

    private ApprovalInboxResponse search(String status, MemberEntity viewer) {
        entityManager.flush();
        entityManager.clear();
        return approvalService.searchApprovals(
                new ApprovalInboxSearchCondition(status, null, null), viewer);
    }

    private MemberEntity saveMember(String studentNumber, String name, String roleName) {
        MemberEntity member =
                MemberFixture.save(
                        memberRepository,
                        memberGradeRepository,
                        memberStatusRepository,
                        UUID.randomUUID(),
                        studentNumber,
                        name,
                        studentNumber + "@sscc.org");
        if (roleName != null) {
            MemberRoleFixture.assign(
                    memberRoleRepository,
                    memberRoleClassificationRepository,
                    memberRoleAssignmentRepository,
                    member,
                    roleName);
        }
        return member;
    }

    private Long createSubWork(long subWorkTypeId, String title) {
        return createSubWork(subWorkTypeId, title, registrant);
    }

    /*
     * 요청자(등록자)만 갈아 끼운다 — 담당자는 언제나 registrant다. 카드의 '요청 …'은 등록자이고,
     * 둘이 같은 사람이면 등록자 연관이 담당자 것을 재사용해 조회 횟수가 실제와 달라진다.
     */
    private Long createSubWork(long subWorkTypeId, String title, MemberEntity requester) {
        return subWorkService
                .createSubWork(
                        new SubWorkCreateRequest(
                                parentWorkId,
                                title,
                                subWorkTypeId,
                                registrant.getId(),
                                START,
                                END,
                                END,
                                OperationPriority.NORMAL,
                                null,
                                null),
                        requester)
                .subWorkId();
    }

    private Long subWorkInReview(long subWorkTypeId, String title) {
        return subWorkInReview(subWorkTypeId, title, registrant);
    }

    private Long subWorkInReview(long subWorkTypeId, String title, MemberEntity requester) {
        Long subWorkId = createSubWork(subWorkTypeId, title, requester);
        transition(subWorkId, TransitionAction.START, null, requester);
        transition(subWorkId, TransitionAction.REQUEST_REVIEW, null, requester);
        return subWorkId;
    }

    private void transition(
            Long subWorkId, TransitionAction action, String reason, MemberEntity performer) {
        entityManager.flush();
        entityManager.clear();
        subWorkService.transitionSubWork(
                subWorkId, new SubWorkTransitionRequest(action, reason), performer);
    }

    private void vote(Long subWorkId, VoteChoice choice, MemberEntity voter) {
        entityManager.flush();
        entityManager.clear();
        subWorkService.voteOnSubWork(subWorkId, new SubWorkVoteRequest(choice), voter);
    }

    private void completeChecklist(Long subWorkId) {
        for (Long itemId : checklistItemIds(subWorkId)) {
            entityManager.flush();
            entityManager.clear();
            subWorkService.updateChecklistItem(
                    subWorkId, itemId, new SubWorkChecklistItemUpdateRequest(true), registrant);
        }
    }

    private void checkFirstChecklistItem(Long subWorkId) {
        entityManager.flush();
        entityManager.clear();
        subWorkService.updateChecklistItem(
                subWorkId,
                checklistItemIds(subWorkId).get(0),
                new SubWorkChecklistItemUpdateRequest(true),
                registrant);
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
