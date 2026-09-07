package org.sscc.ssccopsserver.domain.operation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

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
import org.sscc.ssccopsserver.domain.member.repository.AuthorityRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberChangeHistoryRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeHistoryRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleClassificationRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusHistoryRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.domain.member.service.AuthorityNameFinder;
import org.sscc.ssccopsserver.domain.member.service.AuthorityPolicy;
import org.sscc.ssccopsserver.domain.member.service.MemberInitialHistoryRecorder;
import org.sscc.ssccopsserver.domain.member.service.MemberLinkAttemptLimiter;
import org.sscc.ssccopsserver.domain.member.service.MemberProfileChangeRecorder;
import org.sscc.ssccopsserver.domain.member.service.MemberService;
import org.sscc.ssccopsserver.domain.member.service.MemberServiceImpl;
import org.sscc.ssccopsserver.domain.operation.code.error.OperationErrorCode;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkChecklistItemUpdateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkCreateResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkSearchCondition;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkSearchResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkSummaryResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkTransitionRequest;
import org.sscc.ssccopsserver.domain.operation.dto.WorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.entity.ApprovalStatus;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkChecklistItemEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkTypeEntity;
import org.sscc.ssccopsserver.domain.operation.entity.TransitionAction;
import org.sscc.ssccopsserver.domain.operation.entity.WorkStatus;
import org.sscc.ssccopsserver.domain.operation.entity.WorkType;
import org.sscc.ssccopsserver.domain.operation.repository.OperationRepository;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkApprovalRepository;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkApprovalVoteRepository;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkChecklistItemRepository;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkRejectionRepository;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkRepository;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkReviewRequest;
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
 * 하위 업무 목록 조회(OPS-008)만 다룬다. SubWorkServiceImplTest와 클래스를 나눈 것은
 * 그쪽이 이미 1000줄에 가깝고, 목록은 '여러 건을 여러 상태로 만들어 두는' 픽스처가
 * 단건 시나리오와 달라 섞으면 양쪽 다 읽기 어려워지기 때문이다.
 *
 * @DataJpaTest가 @Configuration을 걸러내므로 JpaAuditingConfig를 명시적으로 들여온다
 * (SubWorkServiceImplTest와 같은 이유).
 */
@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
// AuthorityPolicy는 @Service라 @DataJpaTest 슬라이스에 없다. MemberServiceImpl이 프로필의
// capabilities를 계산하는 데 쓰므로(#9) 정책과 그 Clock만 슬라이스에 들여온다.
@Import({JpaAuditingConfig.class, AuthorityPolicy.class, ClockConfig.class})
@ActiveProfiles("test")
class SubWorkServiceImplSearchTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    // 지연·마감임박 판정의 기준 시각. 고정하지 않으면 날짜가 지나면서 조용히 뒤집힌다
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 8, 20, 12, 0, 0, 0, KST);
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW.toInstant(), KST);

    private static final OffsetDateTime OVERDUE = NOW.minusDays(5);
    private static final OffsetDateTime SOON = NOW.plusDays(2);
    private static final OffsetDateTime LATER = NOW.plusDays(10);

    // 마감일은 오늘인데 마감 시각은 이미 지난 건. 두 칩의 경계가 갈리는 자리다 (#121)
    private static final OffsetDateTime DUE_TODAY = NOW.minusHours(3);

    // 검토요청 시각 — 3일 전은 정체, 2일 전은 아직 아니다 (ssccops#196, 경계는 8/18 0시)
    private static final OffsetDateTime THREE_DAYS_AGO = NOW.minusDays(3);
    private static final OffsetDateTime TWO_DAYS_AGO = NOW.minusDays(2);

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
    @Autowired private MemberChangeHistoryRepository memberChangeHistoryRepository;
    @Autowired private AuthorityPolicy authorityPolicy;
    @Autowired private AuthorityRepository authorityRepository;
    @Autowired private TestEntityManager entityManager;

    private SubWorkService subWorkService;
    private MemberEntity registrant;
    private Long ownerId;
    private Long springMtWorkId;
    private Long expoWorkId;

    /*
     * 시드가 sub_work_type_id를 지정하지 않으므로(IDENTITY 시퀀스 충돌 방지) 유형은
     * 이름으로 찾는다. 1=예산지출처럼 식별자를 박아 두면 시드 순서에 묶인다.
     */
    private Long approvalNeededTypeId;
    private Long approvalFreeTypeId;

    // 완료 점검 항목이 하나도 없는 유형 — 시드에는 없어 테스트에서 직접 만든다 (ssccops#196)
    private Long noChecklistTypeId;

    @BeforeEach
    void setUp() {
        approvalNeededTypeId =
                SubWorkTypeFixture.idOf(subWorkTypeRepository, SubWorkTypeFixture.EXPENDITURE);
        approvalFreeTypeId =
                SubWorkTypeFixture.idOf(subWorkTypeRepository, SubWorkTypeFixture.APPROVAL_FREE);
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
                        new MemberProfileChangeRecorder(memberChangeHistoryRepository),
                        authorityPolicy,
                        new MemberLinkAttemptLimiter(FIXED_CLOCK),
                        FIXED_CLOCK);
        WorkService workService =
                new WorkServiceImpl(
                        operationRepository,
                        workRepository,
                        subWorkRepository,
                        subWorkChecklistItemRepository,
                        memberService,
                        FIXED_CLOCK,
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
                        new AuthorityNameFinder(authorityRepository),
                        new ApprovalAuthorityPolicy(authorityPolicy),
                        new SubWorkOwnershipPolicy(authorityPolicy),
                        new DeadlinePolicy(FIXED_CLOCK),
                        FIXED_CLOCK,
                        entityManager.getEntityManager());

        // 등록자와 담당자를 다른 회원으로 둬 둘이 뒤바뀌면 테스트가 깨지게 한다
        registrant = saveMember("20200001", "김도현", "registrant@sscc.org");
        // 반려 전이를 태우는 목록 테스트가 있어 승인자 역할(예산지출=총무)을 붙여 둔다 (#47)
        MemberRoleFixture.assign(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                registrant,
                MemberRoleFixture.TREASURER);
        ownerId = saveMember("20200002", "이서연", "owner@sscc.org").getId();

        noChecklistTypeId =
                subWorkTypeRepository
                        .save(
                                SubWorkTypeEntity.create(
                                        "점검 없는 유형", false, null, false, null, List.of()))
                        .getId();

        // 상위 업무를 둘 둬 목록이 상위 업무를 가로지르는지 확인한다
        springMtWorkId = createWork(workService, "봄MT");
        expoWorkId = createWork(workService, "2026 동아리 박람회");
    }

    // 필터 없이 부르면 상위 업무를 가로질러 전부 나오고, 상위 업무 배지 값도 함께 실린다
    @Test
    void searchWithoutFilterReturnsEverySubWorkAcrossWorks() {
        Long placeId = createSubWork(springMtWorkId, "봄MT 장소 선정", SOON);
        Long boothId = createSubWork(expoWorkId, "박람회 부스 운영", LATER);

        SubWorkSearchResponse response = search(condition().build());

        assertThat(response.subWorks())
                .extracting(SubWorkSummaryResponse::subWorkId)
                .containsExactly(placeId, boothId);
        assertThat(response.subWorks().get(0).work().workId()).isEqualTo(springMtWorkId);
        assertThat(response.subWorks().get(0).work().title()).isEqualTo("봄MT");
        assertThat(response.subWorks().get(0).subWorkTypeName()).isEqualTo("내부행사");
        assertThat(response.subWorks().get(0).owner().memberId()).isEqualTo(ownerId);
        assertThat(response.subWorks().get(1).work().title()).isEqualTo("2026 동아리 박람회");
    }

    /*
     * 정렬 기본값은 마감 오름차순이고 마감 없는 건은 뒤로 간다 (AGG-04).
     * H2와 PostgreSQL의 NULL 정렬 기본값이 달라 쿼리에 nulls last를 명시한 부분을 지킨다.
     */
    @Test
    void searchOrdersByDueAtAscendingWithNullsLast() {
        Long undated = createSubWork(springMtWorkId, "마감 없는 건", null);
        Long later = createSubWork(springMtWorkId, "늦은 마감", LATER);
        Long soon = createSubWork(springMtWorkId, "이른 마감", SOON);

        assertThat(idsOf(search(condition().build()))).containsExactly(soon, later, undated);
    }

    @Test
    void descendingSortReversesOrderButKeepsNullsLast() {
        Long undated = createSubWork(springMtWorkId, "마감 없는 건", null);
        Long later = createSubWork(springMtWorkId, "늦은 마감", LATER);
        Long soon = createSubWork(springMtWorkId, "이른 마감", SOON);

        SubWorkSearchResponse response = search(condition().sort("-dueAt").build());

        assertThat(idsOf(response)).containsExactly(later, soon, undated);
        assertThat(response.page().sort()).isEqualTo("-dueAt");
    }

    // 마감이 같은 건이 여럿이면 식별자 오름차순으로 끊는다 — 커서가 기대는 순서다
    @Test
    void sameDueAtIsBrokenByIdAscending() {
        Long first = createSubWork(springMtWorkId, "동시 마감 1", SOON);
        Long second = createSubWork(springMtWorkId, "동시 마감 2", SOON);

        assertThat(idsOf(search(condition().build()))).containsExactly(first, second);
    }

    // 화면 '진행' 칩
    @Test
    void workStatusFilterReturnsOnlyThatStatus() {
        Long inProgress = createSubWork(springMtWorkId, "진행 중인 건", SOON);
        transition(inProgress, TransitionAction.START, null);
        createSubWork(springMtWorkId, "기획 상태인 건", SOON);

        assertThat(idsOf(search(condition().workStatus("IN_PROGRESS").build())))
                .containsExactly(inProgress);
    }

    /*
     * 제목 부분 일치 (ssccops#216). 업무 쪽(WorkServiceImplSearchTest)과 **같은 규칙**이어야
     * 한다 — 두 목록이 회의 안건 추가 화면에 나란히 놓이므로 한쪽만 대소문자를 가리거나 한쪽만
     * '%'를 특수문자로 다루면 같은 검색어가 종류를 바꾼 순간 다른 결과를 낸다.
     */
    @Test
    void keywordFilterMatchesPartOfTitle() {
        Long poster = createSubWork(springMtWorkId, "포스터 디자인", SOON);
        createSubWork(springMtWorkId, "예산 정산", SOON);

        assertThat(idsOf(search(condition().keyword("포스터").build()))).containsExactly(poster);
        assertThat(idsOf(search(condition().keyword("없는제목").build()))).isEmpty();
    }

    @Test
    void keywordFilterIgnoresCase() {
        Long subWorkId = createSubWork(springMtWorkId, "SSCC 부스 운영", SOON);

        assertThat(idsOf(search(condition().keyword("sscc").build()))).containsExactly(subWorkId);
    }

    // 공백만인 검색어는 조건 없음이다 (KeywordSearch.normalize)
    @Test
    void blankKeywordIsTreatedAsNoFilter() {
        createSubWork(springMtWorkId, "포스터 디자인", SOON);
        createSubWork(springMtWorkId, "예산 정산", SOON);

        assertThat(idsOf(search(condition().keyword("   ").build()))).hasSize(2);
    }

    // 와일드카드는 리터럴이다 — 업무 쪽과 같은 규칙
    @Test
    void wildcardCharactersAreMatchedLiterally() {
        Long discount = createSubWork(springMtWorkId, "할인 50% 협상", SOON);
        createSubWork(springMtWorkId, "예산 정산", SOON);

        assertThat(idsOf(search(condition().keyword("%").build()))).containsExactly(discount);
        assertThat(idsOf(search(condition().keyword("_").build()))).isEmpty();
    }

    /*
     * **이 작업의 핵심이다** — 커서 페이징 20건이라 화면이 받아 둔 배열을 거르면 첫 페이지
     * 안의 건만 찾아진다.
     */
    @Test
    void keywordFindsRowsBeyondTheFirstPage() {
        for (int i = 0; i < 25; i++) {
            createSubWork(springMtWorkId, "채우기 " + i, SOON);
        }
        Long needle = createSubWork(springMtWorkId, "숨어 있는 하위 업무", SOON);

        assertThat(idsOf(search(condition().size(20).build()))).doesNotContain(needle);
        assertThat(idsOf(search(condition().keyword("숨어").build()))).containsExactly(needle);
    }

    // 화면 우상단의 '8건'도 검색 결과 건수를 말한다
    @Test
    void keywordIsAppliedToCountsAsWell() {
        createSubWork(springMtWorkId, "포스터 디자인", SOON);
        createSubWork(springMtWorkId, "포스터 인쇄", SOON);
        createSubWork(springMtWorkId, "예산 정산", SOON);

        SubWorkSearchResponse response = search(condition().keyword("포스터").build());

        assertThat(response.page().totalCount()).isEqualTo(2);
        assertThat(response.page().overallCount()).isEqualTo(3);
    }

    // 검색어와 기존 필터가 함께 걸린다
    @Test
    void keywordCombinesWithStatusFilter() {
        Long started = createSubWork(springMtWorkId, "포스터 디자인", SOON);
        transition(started, TransitionAction.START, null);
        createSubWork(springMtWorkId, "포스터 인쇄", SOON);

        assertThat(idsOf(search(condition().keyword("포스터").workStatus("IN_PROGRESS").build())))
                .containsExactly(started);
    }

    // 검색어를 건 채 커서로 이어 받아도 같은 조건의 페이지가 이어진다
    @Test
    void cursorPagingKeepsKeywordCondition() {
        for (int i = 0; i < 3; i++) {
            createSubWork(springMtWorkId, "포스터 작업 " + i, SOON);
        }
        createSubWork(springMtWorkId, "무관한 건", SOON);

        SubWorkSearchResponse first = search(condition().keyword("포스터").size(2).build());
        assertThat(first.subWorks()).hasSize(2);
        assertThat(first.page().hasNext()).isTrue();

        SubWorkSearchResponse second =
                search(
                        condition()
                                .keyword("포스터")
                                .size(2)
                                .cursor(first.page().nextCursor())
                                .build());

        assertThat(second.subWorks()).hasSize(1);
        assertThat(second.page().hasNext()).isFalse();
    }

    /*
     * 화면 '승인대기' 칩. 승인이 필요한 유형은 등록 직후부터 승인 상태가 대기(PENDING)라
     * 승인 상태만 걸면 아직 검토요청도 하지 않은 건까지 잡힌다. 업무 상태를 함께 걸어야
     * 승인함에 실제로 뜨는 건과 목록이 일치한다.
     */
    @Test
    void approvalPendingChipExcludesSubWorksNotYetInReview() {
        Long pending = createSubWork(springMtWorkId, "승인 필요한 건", approvalNeededTypeId, SOON);

        assertThat(subWorkRepository.findById(pending).orElseThrow().getApprovalStatus())
                .isEqualTo(ApprovalStatus.PENDING);
        assertThat(idsOf(search(approvalPendingChip()))).isEmpty();

        transition(pending, TransitionAction.START, null);
        transition(pending, TransitionAction.REQUEST_REVIEW, null);

        assertThat(idsOf(search(approvalPendingChip()))).containsExactly(pending);
    }

    // 반려 후 다시 올라온 건은 재승인필요다. 승인자가 처리해야 할 건이므로 같은 칩에 실린다
    @Test
    void approvalPendingChipIncludesReapprovalRequired() {
        Long rejected = createSubWork(springMtWorkId, "반려된 건", approvalNeededTypeId, SOON);
        transition(rejected, TransitionAction.START, null);
        transition(rejected, TransitionAction.REQUEST_REVIEW, null);
        transition(rejected, TransitionAction.REJECT, "예산 근거가 부족합니다.");
        transition(rejected, TransitionAction.REQUEST_REVIEW, null);

        assertThat(subWorkRepository.findById(rejected).orElseThrow().getApprovalStatus())
                .isEqualTo(ApprovalStatus.REAPPROVAL_REQUIRED);
        assertThat(idsOf(search(approvalPendingChip()))).containsExactly(rejected);
    }

    /*
     * 화면 '지연' 칩. dly_yn 컬럼이 아니라 조회 시점 판정이다 — 그 컬럼은 채우지 않기로
     * 결정했으므로(#117) 지연된 건도 저장값은 false이고, 그래도 필터에 잡혀야 한다.
     */
    @Test
    void overdueChipReturnsOnlyPastDueAndUnfinished() {
        Long overdue = createSubWork(springMtWorkId, "마감 지난 건", OVERDUE);
        createSubWork(springMtWorkId, "아직 여유 있는 건", LATER);
        createSubWork(springMtWorkId, "마감 없는 건", null);
        Long doneOverdue = createSubWork(springMtWorkId, "늦게 끝난 건", OVERDUE);
        complete(doneOverdue);

        // 어떤 행도 dly_yn이 켜져 있지 않다. 필터가 컬럼을 읽는다면 아래에서 빈 목록이 나온다
        assertThat(subWorkRepository.findAll())
                .extracting(SubWorkEntity::isDelayed)
                .containsOnly(false);

        SubWorkSearchResponse response = search(condition().isOverdue(true).build());

        assertThat(idsOf(response)).containsExactly(overdue);
        assertThat(response.subWorks().get(0).isDelayed()).isTrue();
    }

    /*
     * 지연 판정은 서버 안에 두 벌 있다 — 단건·요약 응답은 SubWorkEntity.isDelayedBefore가,
     * 목록 필터는 SubWorkRepositoryImpl이 같은 조건을 SQL로 옮겨 쓴다. 한쪽만 고치면 같은
     * 건이 목록에는 '지연'으로 잡히는데 상세에서는 아니게 되므로, 같은 데이터로 두 답이
     * 일치하는지를 여기서 못 박는다 (#194 · 완료 업무가 '지연'으로 표기된 ssccops#112).
     *
     * 케이스는 SubWorkEntityTest와 같은 넷이다 — 마감 지난 미완료 / 마감 지난 완료 /
     * 마감일이 오늘 / 마감 없음. 그중 지연은 첫 번째뿐이다.
     */
    @Test
    void singleJudgementAndOverdueFilterAgreeOnEveryCase() {
        Long overdue = createSubWork(springMtWorkId, "마감 지난 미완료 건", OVERDUE);
        Long doneOverdue = createSubWork(springMtWorkId, "마감 지나 완료한 건", OVERDUE);
        complete(doneOverdue);
        Long dueToday = createSubWork(springMtWorkId, "오늘 아침 마감", DUE_TODAY);
        Long undated = createSubWork(springMtWorkId, "마감 없는 건", null);

        Instant overdueBefore = new DeadlinePolicy(FIXED_CLOCK).overdueBefore();
        entityManager.flush();
        entityManager.clear();

        // 단건 판정
        assertThat(delayedBefore(overdue, overdueBefore)).isTrue();
        assertThat(delayedBefore(doneOverdue, overdueBefore)).isFalse();
        assertThat(delayedBefore(dueToday, overdueBefore)).isFalse();
        assertThat(delayedBefore(undated, overdueBefore)).isFalse();

        // 목록 필터. 단건이 지연이라고 한 건과 정확히 같은 집합이어야 한다
        List<Long> delayedBySingleJudgement =
                subWorkRepository.findAll().stream()
                        .filter(subWork -> subWork.isDelayedBefore(overdueBefore))
                        .map(SubWorkEntity::getId)
                        .toList();

        assertThat(idsOf(search(condition().isOverdue(true).build())))
                .containsExactlyInAnyOrderElementsOf(delayedBySingleJudgement)
                .containsExactly(overdue);

        // 목록에 실리는 지연 플래그도 같은 판정을 거친다 — 완료 건은 여기서도 지연이 아니다
        assertThat(search(condition().build()).subWorks())
                .filteredOn(SubWorkSummaryResponse::isDelayed)
                .extracting(SubWorkSummaryResponse::subWorkId)
                .containsExactly(overdue);
    }

    // isOverdue=false는 '지연이 아닌 건만'이 아니라 필터 없음이다
    @Test
    void overdueFalseIsTreatedAsNoFilter() {
        createSubWork(springMtWorkId, "마감 지난 건", OVERDUE);
        createSubWork(springMtWorkId, "여유 있는 건", LATER);

        assertThat(search(condition().isOverdue(false).build()).subWorks()).hasSize(2);
    }

    /*
     * 화면 '마감임박' 칩. 이미 지난 건과 완료된 건은 빠진다 — 그러지 않으면 '마감임박'이
     * '지연'을 통째로 포함해 두 칩이 겹친다.
     */
    @Test
    void dueBeforeChipExcludesOverdueAndCompleted() {
        Long soon = createSubWork(springMtWorkId, "곧 마감", SOON);
        createSubWork(springMtWorkId, "마감 지난 건", OVERDUE);
        createSubWork(springMtWorkId, "한참 남은 건", LATER);
        Long doneSoon = createSubWork(springMtWorkId, "이미 끝난 건", SOON);
        complete(doneSoon);

        assertThat(idsOf(search(condition().dueBefore(NOW.plusDays(3)).build())))
                .containsExactly(soon);
    }

    /*
     * 마감일이 오늘인 건은 마감 시각이 지났어도 '지연' 칩이 아니라 '마감임박' 칩에 잡힌다
     * (#121). 두 칩이 같은 값(오늘 0시)을 경계로 쓰므로 사이로 빠지는 건이 없다 — 지연만
     * 날짜 단위로 옮기고 마감임박 하한을 '지금'으로 두면 이 건은 어느 칩에서도 보이지 않는다.
     */
    @Test
    void subWorkDueTodayGoesToDueBeforeChipInsteadOfOverdueChip() {
        Long dueToday = createSubWork(springMtWorkId, "오늘 아침 마감", DUE_TODAY);

        assertThat(idsOf(search(condition().isOverdue(true).build()))).isEmpty();
        assertThat(idsOf(search(condition().dueBefore(NOW.plusDays(3)).build())))
                .containsExactly(dueToday);
        assertThat(search(condition().build()).subWorks())
                .extracting(SubWorkSummaryResponse::isDelayed)
                .containsExactly(false);
    }

    // 화면 '완료' 칩
    @Test
    void completedChipReturnsDoneOnly() {
        Long done = createSubWork(springMtWorkId, "완료된 건", SOON);
        complete(done);
        createSubWork(springMtWorkId, "진행 중인 건", SOON);

        SubWorkSearchResponse response = search(condition().workStatus("DONE").build());

        assertThat(idsOf(response)).containsExactly(done);
        assertThat(response.subWorks().get(0).workStatus()).isEqualTo(WorkStatus.DONE);
    }

    /*
     * 진행률은 체크리스트 완료율에서 파생한다 (AGG-02). 완료된 건은 항목 수와 무관하게 100이다.
     * 시드 유형의 점검 항목은 4개라 하나 체크하면 25.00이 된다.
     */
    @Test
    void progressRateComesFromChecklist() {
        Long untouched = createSubWork(springMtWorkId, "체크 전", SOON);
        Long partly = createSubWork(springMtWorkId, "하나 체크", SOON);
        checkFirstChecklistItem(partly);
        Long done = createSubWork(springMtWorkId, "완료된 건", SOON);
        complete(done);

        List<SubWorkSummaryResponse> subWorks = search(condition().build()).subWorks();

        assertThat(progressRateOf(subWorks, untouched))
                .isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(progressRateOf(subWorks, partly)).isEqualByComparingTo(new BigDecimal("25.00"));
        assertThat(progressRateOf(subWorks, done)).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    // 소프트 삭제된 건은 목록에도, 걸러진 건수에도, 전체 건수에도 없다 (AGG-03)
    @Test
    void softDeletedSubWorkIsExcludedFromRowsAndCounts() {
        Long kept = createSubWork(springMtWorkId, "살아있는 건", SOON);
        SubWorkCreateResponse deleted =
                subWorkService.createSubWork(
                        createRequest(springMtWorkId, "삭제된 건", approvalFreeTypeId, SOON),
                        registrant);
        operationRepository
                .findById(deleted.operationId())
                .orElseThrow()
                .softDelete(NOW.toInstant());

        SubWorkSearchResponse response = search(condition().build());

        assertThat(idsOf(response)).containsExactly(kept);
        assertThat(response.page().totalCount()).isEqualTo(1);
        assertThat(response.page().overallCount()).isEqualTo(1);
    }

    /*
     * 정체 ① 칩 — 완료 점검을 다 채웠는데 아직 검토요청 전인 건만 (ssccops#196).
     * 다음에 누를 사람이 담당자인 건들이다.
     */
    @Test
    void readyForReviewChipReturnsOnlyFullyCheckedAndNotYetRequested() {
        Long ready = createSubWork(springMtWorkId, "점검 다 됐는데 요청 전", SOON);
        completeChecklist(ready);
        Long halfway = createSubWork(springMtWorkId, "점검이 남은 건", SOON);
        checkFirstChecklistItem(halfway);
        Long requested = createSubWork(springMtWorkId, "이미 검토요청한 건", SOON);
        completeChecklist(requested);
        requestReview(requested);
        Long finished = createSubWork(springMtWorkId, "완료된 건", SOON);
        complete(finished);

        assertThat(idsOf(search(condition().isReadyForReview(true).build())))
                .containsExactly(ready);
    }

    /*
     * 점검 항목이 없는 유형은 칩에 잡히지 않는다 — 0 == 0이라 '전부 체크'가 공허하게 참이
     * 되면 그 유형의 모든 건이 등록 직후부터 정체로 뜬다.
     */
    @Test
    void typeWithoutChecklistNeverAppearsInReadyForReviewChip() {
        createSubWork(springMtWorkId, "점검 항목이 없는 유형", noChecklistTypeId, SOON);

        assertThat(search(condition().isReadyForReview(true).build()).subWorks()).isEmpty();
    }

    /*
     * 정체 ② 칩 — 검토요청 후 3일이 지났는데 아직 검토 상태인 건만 (ssccops#196).
     * 다음에 누를 사람이 승인자인 건들이다. 2일째는 아직 아니다.
     */
    @Test
    void reviewStaleChipReturnsOnlyRequestsOlderThanThreeDays() {
        Long stale = subWorkRequestedAt(THREE_DAYS_AGO, "3일 전에 올라온 건");
        subWorkRequestedAt(TWO_DAYS_AGO, "2일 전에 올라온 건");
        createSubWork(springMtWorkId, "아직 요청 전인 건", SOON);

        assertThat(idsOf(search(condition().isReviewStale(true).build()))).containsExactly(stale);
    }

    // 반려돼 진행으로 돌아간 건은 옛 요청 시각이 이력에 남지만 대기 중인 요청이 아니다
    @Test
    void rejectedSubWorkLeavesTheReviewStaleChip() {
        Long rejected = subWorkRequestedAt(THREE_DAYS_AGO, "3일 전에 올라왔다 반려된 건");
        transition(rejected, TransitionAction.REJECT, "예산 근거가 부족합니다");

        assertThat(search(condition().isReviewStale(true).build()).subWorks()).isEmpty();
    }

    /*
     * 단건 판정(엔티티)과 목록 필터(SQL)가 모든 경우에 같은 답을 내는지. 규칙이 두 벌로
     * 적혀 있어 한쪽만 고치면 갈리며, 지연 판정이 실제로 그렇게 두 번 갈렸다 (#121 · #194).
     */
    @Test
    void stallJudgementAndFiltersAgreeOnEveryCase() {
        Long ready = createSubWork(springMtWorkId, "점검 다 됐는데 요청 전", SOON);
        completeChecklist(ready);
        Long halfway = createSubWork(springMtWorkId, "점검이 남은 건", SOON);
        checkFirstChecklistItem(halfway);
        Long noChecklist = createSubWork(springMtWorkId, "점검 없는 유형", noChecklistTypeId, SOON);
        Long stale = subWorkRequestedAt(THREE_DAYS_AGO, "3일 전에 올라온 건");
        Long fresh = subWorkRequestedAt(TWO_DAYS_AGO, "2일 전에 올라온 건");
        Long finished = createSubWork(springMtWorkId, "완료된 건", SOON);
        complete(finished);
        Instant reviewStaleBefore = new DeadlinePolicy(FIXED_CLOCK).reviewStaleBefore();

        // 엔티티에게 직접 묻는다
        assertThat(readyForReview(ready)).isTrue();
        assertThat(readyForReview(halfway)).isFalse();
        assertThat(readyForReview(noChecklist)).isFalse();
        assertThat(readyForReview(finished)).isFalse();
        assertThat(reviewStale(stale, reviewStaleBefore)).isTrue();
        assertThat(reviewStale(fresh, reviewStaleBefore)).isFalse();
        assertThat(reviewStale(finished, reviewStaleBefore)).isFalse();

        // 목록 필터가 같은 집합을 돌려준다
        assertThat(idsOf(search(condition().isReadyForReview(true).build())))
                .containsExactly(ready);
        assertThat(idsOf(search(condition().isReviewStale(true).build()))).containsExactly(stale);

        // 필터를 뗀 목록의 응답 플래그도 같은 답이다
        List<SubWorkSummaryResponse> rows = search(condition().build()).subWorks();
        assertThat(idsWhere(rows, SubWorkSummaryResponse::isReadyForReview)).containsExactly(ready);
        assertThat(idsWhere(rows, SubWorkSummaryResponse::isReviewStale)).containsExactly(stale);
    }

    // true만 필터다 — false·생략은 '정체가 아닌 것만'이 아니라 필터 없음이다 (isOverdue와 같다)
    @Test
    void stallFiltersSetToFalseAreTreatedAsNoFilter() {
        createSubWork(springMtWorkId, "하위 업무 1", SOON);
        createSubWork(springMtWorkId, "하위 업무 2", LATER);

        assertThat(search(condition().isReadyForReview(false).build()).subWorks()).hasSize(2);
        assertThat(search(condition().isReviewStale(false).build()).subWorks()).hasSize(2);
    }

    /*
     * 검토 중인 건이 섞인 페이지는 검토요청 시각 집계가 하나 더 붙어 다섯 번이다
     * (ssccops#196). 행이 몇 건이든 이 수는 그대로다 — 카드마다 이력을 물으면 N+1이다.
     */
    @Test
    void searchRunsFiveQueriesWhenRowsIncludeReviewRegardlessOfRowCount() {
        for (int index = 0; index < 5; index++) {
            subWorkRequestedAt(THREE_DAYS_AGO, "검토 중인 건 " + index);
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

        SubWorkSearchResponse response = subWorkService.searchSubWorks(condition().build());

        assertThat(response.subWorks()).hasSize(5);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(5);
    }

    // 건수는 두 갈래다 — 걸러진 건수(화면 '8건')와 필터를 뗀 전체 건수(화면 '전체 8건')
    @Test
    void pageCarriesFilteredCountAndOverallCount() {
        Long done = createSubWork(springMtWorkId, "완료된 건", SOON);
        complete(done);
        createSubWork(springMtWorkId, "진행 중인 건", SOON);
        createSubWork(expoWorkId, "다른 상위 업무의 건", LATER);

        SubWorkSearchResponse response = search(condition().workStatus("DONE").build());

        assertThat(response.page().totalCount()).isEqualTo(1);
        assertThat(response.page().overallCount()).isEqualTo(3);
    }

    // 커서로 이어 읽어도 항목이 중복되거나 빠지지 않는다 — 마감 없는 구간을 건널 때도 마찬가지다
    @Test
    void cursorPagingReadsEveryRowExactlyOnce() {
        createSubWork(springMtWorkId, "마감 없는 건 1", null);
        createSubWork(springMtWorkId, "마감 없는 건 2", null);
        createSubWork(springMtWorkId, "같은 마감 1", SOON);
        createSubWork(springMtWorkId, "같은 마감 2", SOON);
        createSubWork(expoWorkId, "늦은 마감", LATER);

        List<Long> expected = idsOf(search(condition().build()));
        List<Long> paged = new ArrayList<>();
        String cursor = null;
        do {
            SubWorkSearchResponse page = search(condition().size(2).cursor(cursor).build());
            paged.addAll(idsOf(page));
            cursor = page.page().nextCursor();
        } while (cursor != null);

        assertThat(expected).hasSize(5);
        assertThat(paged).containsExactlyElementsOf(expected);
    }

    @Test
    void lastPageHasNoCursor() {
        createSubWork(springMtWorkId, "하위 업무 1", SOON);
        createSubWork(springMtWorkId, "하위 업무 2", LATER);

        SubWorkSearchResponse firstPage = search(condition().size(1).build());
        assertThat(firstPage.page().hasNext()).isTrue();
        assertThat(firstPage.page().nextCursor()).isNotBlank();
        assertThat(firstPage.page().size()).isEqualTo(1);

        SubWorkSearchResponse lastPage =
                search(condition().size(1).cursor(firstPage.page().nextCursor()).build());
        assertThat(lastPage.page().hasNext()).isFalse();
        assertThat(lastPage.page().nextCursor()).isNull();
    }

    // 목록이 비어도 200이고 빈 목록이다. 건수는 그대로 내려간다
    @Test
    void emptyResultKeepsCountsAndReturnsNoRows() {
        createSubWork(springMtWorkId, "기획 상태인 건", SOON);

        SubWorkSearchResponse response = search(condition().workStatus("DONE").build());

        assertThat(response.subWorks()).isEmpty();
        assertThat(response.page().hasNext()).isFalse();
        assertThat(response.page().nextCursor()).isNull();
        assertThat(response.page().totalCount()).isZero();
        assertThat(response.page().overallCount()).isEqualTo(1);
    }

    // 정렬이 다른 커서는 조용히 첫 페이지로 되돌리지 않고 막는다 — 결과가 어긋난 채 그려진다
    @Test
    void cursorFromAnotherSortIsRejected() {
        createSubWork(springMtWorkId, "하위 업무 1", SOON);
        createSubWork(springMtWorkId, "하위 업무 2", LATER);
        String cursor = search(condition().size(1).build()).page().nextCursor();

        assertThatThrownBy(() -> search(condition().size(1).cursor(cursor).sort("-dueAt").build()))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.INVALID_CURSOR);
    }

    @Test
    void malformedCursorIsRejected() {
        assertThatThrownBy(() -> search(condition().cursor("!!not-a-cursor!!").build()))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.INVALID_CURSOR);
    }

    @Test
    void unknownStatusCodeIsRejected() {
        assertThatThrownBy(() -> search(condition().workStatus("진행").build()))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(CommonErrorCode.INVALID_CODE_VALUE);
    }

    // 오타 난 정렬을 기본값으로 떨어뜨리면 클라이언트는 서버가 정렬해 준 줄 안다
    @Test
    void unknownSortIsRejected() {
        assertThatThrownBy(() -> search(condition().sort("dueDate").build()))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(CommonErrorCode.INVALID_CODE_VALUE);
    }

    /*
     * 하위 업무마다 체크리스트를 세면 그대로 N+1이 된다 (DB-13). 목록 1 + 진행률 집계 1 +
     * 걸러진 건수 1 + 전체 건수 1로 끝나는지 못 박아 둔다 — 행이 몇 건이든 이 수는 그대로다.
     */
    @Test
    void searchRunsFourQueriesRegardlessOfRowCount() {
        for (int index = 0; index < 5; index++) {
            createSubWork(springMtWorkId, "하위 업무 " + index, SOON.plusDays(index));
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

        SubWorkSearchResponse response = subWorkService.searchSubWorks(condition().build());

        assertThat(response.subWorks()).hasSize(5);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(4);
    }

    /*
     * 인증 컨버터가 더 이상 회원을 만들지 않으므로(가입 API가 유일한 생성 경로다) 회원은
     * 픽스처로 직접 만든다.
     */
    private MemberEntity saveMember(String studentNumber, String name, String email) {
        return MemberFixture.save(
                memberRepository,
                memberGradeRepository,
                memberStatusRepository,
                UUID.randomUUID(),
                studentNumber,
                name,
                email);
    }

    private Long createWork(WorkService workService, String title) {
        return workService
                .createWork(
                        new WorkCreateRequest(
                                title, WorkType.EVENT, ownerId, null, null, null, null),
                        registrant)
                .workId();
    }

    private Long createSubWork(Long workId, String title, OffsetDateTime dueAt) {
        return createSubWork(workId, title, approvalFreeTypeId, dueAt);
    }

    private Long createSubWork(Long workId, String title, long typeId, OffsetDateTime dueAt) {
        return subWorkService
                .createSubWork(createRequest(workId, title, typeId, dueAt), registrant)
                .subWorkId();
    }

    private SubWorkCreateRequest createRequest(
            Long workId, String title, long typeId, OffsetDateTime dueAt) {
        return new SubWorkCreateRequest(
                workId, title, typeId, ownerId, null, null, dueAt, null, null, null);
    }

    // 정상 경로로 완료까지 올린다 (TR-01 → TR-02 → 체크리스트 충족 → TR-03)
    private void complete(Long subWorkId) {
        transition(subWorkId, TransitionAction.START, null);
        transition(subWorkId, TransitionAction.REQUEST_REVIEW, null);
        for (Long itemId : checklistItemIds(subWorkId)) {
            checkItem(subWorkId, itemId);
        }
        transition(subWorkId, TransitionAction.APPROVE_COMPLETE, null);
    }

    private void checkFirstChecklistItem(Long subWorkId) {
        checkItem(subWorkId, checklistItemIds(subWorkId).get(0));
    }

    private void checkItem(Long subWorkId, Long itemId) {
        entityManager.flush();
        entityManager.clear();
        subWorkService.updateChecklistItem(
                subWorkId, itemId, new SubWorkChecklistItemUpdateRequest(true), registrant);
    }

    private List<Long> checklistItemIds(Long subWorkId) {
        entityManager.flush();
        entityManager.clear();
        SubWorkEntity subWork = subWorkRepository.findById(subWorkId).orElseThrow();
        return subWorkChecklistItemRepository.findBySubWorkOrderBySortOrderAsc(subWork).stream()
                .map(SubWorkChecklistItemEntity::getId)
                .toList();
    }

    private void transition(Long subWorkId, TransitionAction action, String reason) {
        entityManager.flush();
        entityManager.clear();
        subWorkService.transitionSubWork(
                subWorkId, new SubWorkTransitionRequest(action, reason), registrant);
    }

    private SubWorkSearchResponse search(SubWorkSearchCondition condition) {
        entityManager.flush();
        entityManager.clear();
        return subWorkService.searchSubWorks(condition);
    }

    // 목록이 아니라 엔티티에게 직접 묻는다 — 목록 필터와 답이 갈리는지 보려면 두 경로가 필요하다
    private boolean delayedBefore(Long subWorkId, Instant overdueBefore) {
        return subWorkRepository.findById(subWorkId).orElseThrow().isDelayedBefore(overdueBefore);
    }

    private boolean readyForReview(Long subWorkId) {
        entityManager.flush();
        entityManager.clear();
        SubWorkEntity subWork = subWorkRepository.findById(subWorkId).orElseThrow();
        long total =
                subWorkChecklistItemRepository.findBySubWorkOrderBySortOrderAsc(subWork).size();
        long completed =
                subWorkChecklistItemRepository.findBySubWorkOrderBySortOrderAsc(subWork).stream()
                        .filter(SubWorkChecklistItemEntity::isCompleted)
                        .count();
        return subWork.isReadyForReview(completed, total);
    }

    private boolean reviewStale(Long subWorkId, Instant reviewStaleBefore) {
        entityManager.flush();
        entityManager.clear();
        SubWorkEntity subWork = subWorkRepository.findById(subWorkId).orElseThrow();
        Instant requestedAt =
                subWorkStatusHistoryRepository
                        .findReviewRequestsBySubWorkIds(List.of(subWorkId), WorkStatus.REVIEW)
                        .stream()
                        .findFirst()
                        .map(SubWorkReviewRequest::getLastRequestedAt)
                        .orElse(null);
        return subWork.isReviewStaleBefore(requestedAt, reviewStaleBefore);
    }

    /*
     * 검토요청이 과거에 올라온 건을 만든다. 서비스는 고정 Clock을 쓰므로 요청 시각을
     * 뒤로 미는 방법은 기록된 이력의 시각을 바꾸는 것뿐이다 — 상태는 정상 전이로 만들고
     * 시각만 갈아 끼운다.
     */
    private Long subWorkRequestedAt(OffsetDateTime requestedAt, String title) {
        Long subWorkId = createSubWork(springMtWorkId, title, SOON);
        transition(subWorkId, TransitionAction.START, null);
        transition(subWorkId, TransitionAction.REQUEST_REVIEW, null);
        entityManager.flush();
        entityManager.clear();
        entityManager
                .getEntityManager()
                .createQuery(
                        "update SubWorkStatusHistoryEntity h set h.changedAt = :changedAt"
                                + " where h.subWork.id = :subWorkId"
                                + " and h.nextWorkStatus = :reviewStatus")
                .setParameter("changedAt", requestedAt.toInstant())
                .setParameter("subWorkId", subWorkId)
                .setParameter("reviewStatus", WorkStatus.REVIEW)
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();
        return subWorkId;
    }

    private void completeChecklist(Long subWorkId) {
        for (Long itemId : checklistItemIds(subWorkId)) {
            checkItem(subWorkId, itemId);
        }
    }

    private void requestReview(Long subWorkId) {
        transition(subWorkId, TransitionAction.START, null);
        transition(subWorkId, TransitionAction.REQUEST_REVIEW, null);
    }

    private static List<Long> idsWhere(
            List<SubWorkSummaryResponse> rows, Predicate<SubWorkSummaryResponse> matches) {
        return rows.stream().filter(matches).map(SubWorkSummaryResponse::subWorkId).toList();
    }

    private static List<Long> idsOf(SubWorkSearchResponse response) {
        return response.subWorks().stream().map(SubWorkSummaryResponse::subWorkId).toList();
    }

    private static BigDecimal progressRateOf(
            List<SubWorkSummaryResponse> subWorks, Long subWorkId) {
        return subWorks.stream()
                .filter(subWork -> subWork.subWorkId().equals(subWorkId))
                .findFirst()
                .orElseThrow()
                .progressRate();
    }

    private static SubWorkSearchCondition approvalPendingChip() {
        return condition()
                .workStatus("REVIEW")
                .approvalStatus(List.of("PENDING", "REAPPROVAL_REQUIRED"))
                .build();
    }

    private static ConditionBuilder condition() {
        return new ConditionBuilder();
    }

    /*
     * 쿼리 파라미터가 일곱 개라 테스트마다 null을 늘어놓으면 어느 자리가 무엇인지 읽히지 않는다.
     * 프로덕션 코드에는 빌더를 두지 않는다 — 스프링이 쿼리 파라미터를 그대로 바인딩하므로
     * 필요한 곳이 테스트뿐이다.
     */
    private static final class ConditionBuilder {

        private String workStatus;
        private List<String> approvalStatus;
        private Boolean isOverdue;
        private OffsetDateTime dueBefore;
        private Boolean isReadyForReview;
        private Boolean isReviewStale;
        private String keyword;
        private Integer size;
        private String cursor;
        private String sort;

        private ConditionBuilder workStatus(String value) {
            this.workStatus = value;
            return this;
        }

        private ConditionBuilder approvalStatus(List<String> value) {
            this.approvalStatus = value;
            return this;
        }

        private ConditionBuilder isOverdue(Boolean value) {
            this.isOverdue = value;
            return this;
        }

        private ConditionBuilder dueBefore(OffsetDateTime value) {
            this.dueBefore = value;
            return this;
        }

        private ConditionBuilder isReadyForReview(Boolean value) {
            this.isReadyForReview = value;
            return this;
        }

        private ConditionBuilder isReviewStale(Boolean value) {
            this.isReviewStale = value;
            return this;
        }

        private ConditionBuilder keyword(String value) {
            this.keyword = value;
            return this;
        }

        private ConditionBuilder size(Integer value) {
            this.size = value;
            return this;
        }

        private ConditionBuilder cursor(String value) {
            this.cursor = value;
            return this;
        }

        private ConditionBuilder sort(String value) {
            this.sort = value;
            return this;
        }

        private SubWorkSearchCondition build() {
            return new SubWorkSearchCondition(
                    workStatus,
                    approvalStatus,
                    isOverdue,
                    dueBefore,
                    isReadyForReview,
                    isReviewStale,
                    keyword,
                    size,
                    cursor,
                    sort);
        }
    }
}
