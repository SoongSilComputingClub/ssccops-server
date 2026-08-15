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
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusHistoryRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.domain.member.service.AuthorityPolicy;
import org.sscc.ssccopsserver.domain.member.service.MemberInitialHistoryRecorder;
import org.sscc.ssccopsserver.domain.member.service.MemberService;
import org.sscc.ssccopsserver.domain.member.service.MemberServiceImpl;
import org.sscc.ssccopsserver.domain.operation.code.error.OperationErrorCode;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkChecklistItemUpdateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkTransitionRequest;
import org.sscc.ssccopsserver.domain.operation.dto.WorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.WorkCreateResponse;
import org.sscc.ssccopsserver.domain.operation.dto.WorkListItemResponse;
import org.sscc.ssccopsserver.domain.operation.dto.WorkSearchCondition;
import org.sscc.ssccopsserver.domain.operation.dto.WorkSearchResponse;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkChecklistItemEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.TransitionAction;
import org.sscc.ssccopsserver.domain.operation.entity.WorkStatus;
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

/*
 * 상위 업무 목록 조회(OPS-020)만 다룬다. WorkServiceImplTest와 클래스를 나눈 것은 목록이
 * '여러 업무를 여러 상태로, 그 아래 하위 업무까지 만들어 두는' 픽스처를 쓰기 때문이다
 * (SubWorkServiceImplSearchTest와 같은 이유).
 *
 * @DataJpaTest가 @Configuration을 걸러내므로 JpaAuditingConfig를 명시적으로 들여온다.
 */
@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
// AuthorityPolicy는 @Service라 @DataJpaTest 슬라이스에 없다. MemberServiceImpl이 프로필의
// capabilities를 계산하는 데 쓰므로(#9) 정책과 그 Clock만 슬라이스에 들여온다.
@Import({JpaAuditingConfig.class, AuthorityPolicy.class, ClockConfig.class})
@ActiveProfiles("test")
class WorkServiceImplSearchTest {

    // data.sql이 넣는 유형. 3=내부행사(승인 불필요). 점검 항목 4개라 하나 체크하면 25.00이다
    private static final long APPROVAL_FREE_TYPE_ID = 3L;

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 8, 20, 12, 0, 0, 0, KST);
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW.toInstant(), KST);

    private static final OffsetDateTime EARLY = NOW.minusDays(30);
    private static final OffsetDateTime LATE = NOW.plusDays(30);

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
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberGradeHistoryRepository memberGradeHistoryRepository;
    @Autowired private MemberStatusHistoryRepository memberStatusHistoryRepository;
    @Autowired private AuthorityPolicy authorityPolicy;
    @Autowired private TestEntityManager entityManager;

    private WorkService workService;
    private SubWorkService subWorkService;
    private MemberEntity registrant;
    private Long ownerId;

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
                        FIXED_CLOCK);
        workService =
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

        // 등록자와 담당자를 다른 회원으로 둬 둘이 뒤바뀌면 테스트가 깨지게 한다
        registrant = saveMember("20200001", "김도현", "registrant@sscc.org");
        ownerId = saveMember("20200002", "박지훈", "owner@sscc.org").getId();
    }

    // 카드 한 장이 그리는 값이 모두 실린다 — 상태·유형 배지, 제목, 담당자, 기간
    @Test
    void searchWithoutFilterReturnsCardFields() {
        Long workId = createWork("2026 동아리 박람회", WorkType.EVENT, EARLY, LATE).workId();

        WorkSearchResponse response = search(condition().build());

        assertThat(response.works()).hasSize(1);
        WorkListItemResponse card = response.works().get(0);
        assertThat(card.workId()).isEqualTo(workId);
        assertThat(card.title()).isEqualTo("2026 동아리 박람회");
        assertThat(card.workType()).isEqualTo(WorkType.EVENT);
        assertThat(card.workStatus()).isEqualTo(WorkStatus.PLANNING);
        assertThat(card.owner().memberId()).isEqualTo(ownerId);
        assertThat(card.owner().name()).isEqualTo("박지훈");
        assertThat(card.startAt()).isEqualTo(EARLY);
        assertThat(card.endAt()).isEqualTo(LATE);
    }

    /*
     * 정렬 기본값은 등록 최신순이다 (AGG-06). 등록 시각을 직접 박아 두는 것은 픽스처가
     * 밀리초 안에 다 만들어져 서로 같은 crt_dt를 가지면 순서가 식별자 오름차순으로만 갈려
     * 이 테스트가 조용히 반대 결과를 통과시키기 때문이다.
     */
    @Test
    void defaultSortIsNewestRegisteredFirst() {
        Long oldest = createWorkRegisteredAt("가장 먼저 등록", NOW.minusDays(3).toInstant());
        Long middle = createWorkRegisteredAt("그다음 등록", NOW.minusDays(2).toInstant());
        Long newest = createWorkRegisteredAt("가장 나중 등록", NOW.minusDays(1).toInstant());

        WorkSearchResponse response = search(condition().build());

        assertThat(idsOf(response)).containsExactly(newest, middle, oldest);
        assertThat(response.page().sort()).isEqualTo("-createdAt");
    }

    @Test
    void ascendingCreatedAtSortReversesOrder() {
        Long oldest = createWorkRegisteredAt("가장 먼저 등록", NOW.minusDays(3).toInstant());
        Long newest = createWorkRegisteredAt("가장 나중 등록", NOW.minusDays(1).toInstant());

        assertThat(idsOf(search(condition().sort("createdAt").build())))
                .containsExactly(oldest, newest);
    }

    /*
     * 시작 일시 정렬에서는 시작 일시 없는 업무가 뒤로 간다 (AGG-06). H2와 PostgreSQL의
     * NULL 정렬 기본값이 달라 쿼리에 nulls last를 명시한 부분을 지킨다.
     */
    @Test
    void startAtSortPutsWorksWithoutStartAtLast() {
        Long undated = createWork("시작 일시 없는 업무", WorkType.ROUTINE, null, null).workId();
        Long late = createWork("늦게 시작", WorkType.EVENT, LATE, null).workId();
        Long early = createWork("먼저 시작", WorkType.EVENT, EARLY, null).workId();

        assertThat(idsOf(search(condition().sort("startAt").build())))
                .containsExactly(early, late, undated);
        assertThat(idsOf(search(condition().sort("-startAt").build())))
                .containsExactly(late, early, undated);
    }

    // 정렬 키가 같으면 식별자 오름차순으로 끊는다 — 커서가 기대는 순서다
    @Test
    void sameSortKeyIsBrokenByIdAscending() {
        Long first = createWork("같은 시작일 1", WorkType.EVENT, EARLY, null).workId();
        Long second = createWork("같은 시작일 2", WorkType.EVENT, EARLY, null).workId();

        assertThat(idsOf(search(condition().sort("startAt").build())))
                .containsExactly(first, second);
    }

    // 카드 좌상단의 상태 배지와 같은 축
    @Test
    void workStatusFilterReturnsOnlyThatStatus() {
        Long planning = createWork("기획 상태", WorkType.EVENT, null, null).workId();

        assertThat(idsOf(search(condition().workStatus("PLANNING").build())))
                .containsExactly(planning);
        assertThat(idsOf(search(condition().workStatus("DONE").build()))).isEmpty();
    }

    @Test
    void workTypeFilterReturnsOnlyThatType() {
        Long event = createWork("행사", WorkType.EVENT, null, null).workId();
        createWork("정례운영", WorkType.ROUTINE, null, null);

        assertThat(idsOf(search(condition().workType("EVENT").build()))).containsExactly(event);
    }

    @Test
    void statusAndTypeFiltersAreAppliedTogether() {
        Long eventPlanning = createWork("행사·기획", WorkType.EVENT, null, null).workId();
        createWork("상시·기획", WorkType.REGULAR, null, null);

        assertThat(idsOf(search(condition().workStatus("PLANNING").workType("EVENT").build())))
                .containsExactly(eventPlanning);
    }

    /*
     * 진행률은 하위 업무 진행률의 평균이다 (AGG-01). 하나는 4개 중 1개를 체크해 25.00,
     * 다른 하나는 완료라 100.00이므로 카드에는 62.50이 실린다.
     *
     * 저장 컬럼(work_prgrs_rt)은 완료 개수 비율이라 같은 시점에 50.00이다. 응답이 그 값을
     * 읽지 않는다는 것을 여기서 못 박는다 (AGG-05).
     */
    @Test
    void progressRateAveragesSubWorkRatesInsteadOfStoredColumn() {
        Long workId = createWork("봄MT", WorkType.EVENT, null, null).workId();
        Long partly = createSubWork(workId, "부분 진행");
        checkFirstChecklistItem(partly);
        Long done = createSubWork(workId, "완료된 건");
        complete(done);

        WorkListItemResponse card = search(condition().build()).works().get(0);

        assertThat(card.progressRate()).isEqualByComparingTo(new BigDecimal("62.50"));
        assertThat(card.subWorkCount()).isEqualTo(2);
        assertThat(workRepository.findById(workId).orElseThrow().getProgressRate())
                .isEqualByComparingTo(new BigDecimal("50.00"));
    }

    // 하위 업무가 없으면 진행률은 0이고 건수도 0이다. 분모가 0인 나눗셈을 하지 않는다
    @Test
    void workWithoutSubWorksHasZeroProgressAndCount() {
        createWork("하위 업무 없는 업무", WorkType.REGULAR, null, null);

        WorkListItemResponse card = search(condition().build()).works().get(0);

        assertThat(card.progressRate()).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(card.subWorkCount()).isZero();
    }

    // 목록과 상세(OPS-003)가 같은 업무를 다른 %로 보여주면 안 된다 — 산식이 같은 AGG-01이다
    @Test
    void progressRateMatchesWorkDetail() {
        Long workId = createWork("봄MT", WorkType.EVENT, null, null).workId();
        checkFirstChecklistItem(createSubWork(workId, "부분 진행"));
        createSubWork(workId, "손대지 않은 건");

        WorkListItemResponse card = search(condition().build()).works().get(0);

        assertThat(card.progressRate())
                .isEqualByComparingTo(workService.getWork(workId).progressRate());
        assertThat(card.subWorkCount()).isEqualTo(workService.getWork(workId).subWorkCount());
    }

    // 소프트 삭제된 업무는 목록에도, 걸러진 건수에도, 전체 건수에도 없다 (AGG-03)
    @Test
    void softDeletedWorkIsExcludedFromRowsAndCounts() {
        Long kept = createWork("살아있는 업무", WorkType.EVENT, null, null).workId();
        WorkCreateResponse deleted = createWork("삭제된 업무", WorkType.EVENT, null, null);
        softDelete(deleted.operationId());

        WorkSearchResponse response = search(condition().build());

        assertThat(idsOf(response)).containsExactly(kept);
        assertThat(response.page().totalCount()).isEqualTo(1);
        assertThat(response.page().overallCount()).isEqualTo(1);
    }

    // 삭제된 하위 업무는 카드의 건수에서도 진행률 분모에서도 빠진다 (AGG-03)
    @Test
    void softDeletedSubWorkIsExcludedFromCountAndProgress() {
        Long workId = createWork("봄MT", WorkType.EVENT, null, null).workId();
        Long kept = createSubWork(workId, "살아있는 하위 업무");
        complete(kept);
        Long deleted = createSubWork(workId, "삭제된 하위 업무");
        softDelete(subWorkRepository.findById(deleted).orElseThrow().getOperation().getId());

        WorkListItemResponse card = search(condition().build()).works().get(0);

        assertThat(card.subWorkCount()).isEqualTo(1);
        assertThat(card.progressRate()).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    // 건수는 두 갈래다 — 걸러진 건수와 필터를 뗀 전체 건수
    @Test
    void pageCarriesFilteredCountAndOverallCount() {
        createWork("행사", WorkType.EVENT, null, null);
        createWork("정례운영", WorkType.ROUTINE, null, null);
        createWork("상시", WorkType.REGULAR, null, null);

        WorkSearchResponse response = search(condition().workType("EVENT").build());

        assertThat(response.page().totalCount()).isEqualTo(1);
        assertThat(response.page().overallCount()).isEqualTo(3);
    }

    // 커서로 이어 읽어도 항목이 중복되거나 빠지지 않는다 — 시작 일시 없는 구간을 건널 때도 마찬가지다
    @Test
    void cursorPagingReadsEveryRowExactlyOnce() {
        createWork("시작 일시 없는 업무 1", WorkType.ROUTINE, null, null);
        createWork("시작 일시 없는 업무 2", WorkType.ROUTINE, null, null);
        createWork("같은 시작일 1", WorkType.EVENT, EARLY, null);
        createWork("같은 시작일 2", WorkType.EVENT, EARLY, null);
        createWork("늦게 시작", WorkType.EVENT, LATE, null);

        List<Long> expected = idsOf(search(condition().sort("startAt").build()));
        List<Long> paged = new ArrayList<>();
        String cursor = null;
        do {
            WorkSearchResponse page =
                    search(condition().sort("startAt").size(2).cursor(cursor).build());
            paged.addAll(idsOf(page));
            cursor = page.page().nextCursor();
        } while (cursor != null);

        assertThat(expected).hasSize(5);
        assertThat(paged).containsExactlyElementsOf(expected);
    }

    // 기본 정렬(등록 최신순)로도 이어 읽기가 성립해야 한다
    @Test
    void cursorPagingWorksOnDefaultSort() {
        createWorkRegisteredAt("업무 1", NOW.minusDays(3).toInstant());
        createWorkRegisteredAt("업무 2", NOW.minusDays(2).toInstant());
        createWorkRegisteredAt("업무 3", NOW.minusDays(1).toInstant());

        List<Long> expected = idsOf(search(condition().build()));
        List<Long> paged = new ArrayList<>();
        String cursor = null;
        do {
            WorkSearchResponse page = search(condition().size(1).cursor(cursor).build());
            paged.addAll(idsOf(page));
            cursor = page.page().nextCursor();
        } while (cursor != null);

        assertThat(paged).containsExactlyElementsOf(expected);
    }

    @Test
    void lastPageHasNoCursor() {
        createWork("업무 1", WorkType.EVENT, null, null);
        createWork("업무 2", WorkType.EVENT, null, null);

        WorkSearchResponse firstPage = search(condition().size(1).build());
        assertThat(firstPage.page().hasNext()).isTrue();
        assertThat(firstPage.page().nextCursor()).isNotBlank();
        assertThat(firstPage.page().size()).isEqualTo(1);

        WorkSearchResponse lastPage =
                search(condition().size(1).cursor(firstPage.page().nextCursor()).build());
        assertThat(lastPage.page().hasNext()).isFalse();
        assertThat(lastPage.page().nextCursor()).isNull();
    }

    // 목록이 비어도 빈 목록이다. 건수는 그대로 내려간다
    @Test
    void emptyResultKeepsCountsAndReturnsNoRows() {
        createWork("행사", WorkType.EVENT, null, null);

        WorkSearchResponse response = search(condition().workType("ROUTINE").build());

        assertThat(response.works()).isEmpty();
        assertThat(response.page().hasNext()).isFalse();
        assertThat(response.page().nextCursor()).isNull();
        assertThat(response.page().totalCount()).isZero();
        assertThat(response.page().overallCount()).isEqualTo(1);
    }

    // 정렬이 다른 커서는 조용히 첫 페이지로 되돌리지 않고 막는다 — 결과가 어긋난 채 그려진다
    @Test
    void cursorFromAnotherSortIsRejected() {
        createWork("업무 1", WorkType.EVENT, EARLY, null);
        createWork("업무 2", WorkType.EVENT, LATE, null);
        String cursor = search(condition().size(1).build()).page().nextCursor();

        assertThatThrownBy(() -> search(condition().size(1).cursor(cursor).sort("startAt").build()))
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

    // 정의서 원본의 한글 표기(기획·진행)는 저장 코드가 아니다
    @Test
    void unknownStatusCodeIsRejected() {
        assertThatThrownBy(() -> search(condition().workStatus("기획").build()))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(CommonErrorCode.INVALID_CODE_VALUE);
    }

    @Test
    void unknownTypeCodeIsRejected() {
        assertThatThrownBy(() -> search(condition().workType("FESTIVAL").build()))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(CommonErrorCode.INVALID_CODE_VALUE);
    }

    // 오타 난 정렬을 기본값으로 떨어뜨리면 클라이언트는 서버가 정렬해 준 줄 안다
    @Test
    void unknownSortIsRejected() {
        assertThatThrownBy(() -> search(condition().sort("createdDate").build()))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(CommonErrorCode.INVALID_CODE_VALUE);
    }

    /*
     * 진행률은 업무 → 하위 업무 → 체크리스트로 3단이라 그대로 두면 N+1이 두 겹으로 쌓인다
     * (DB-13). 목록 1 + 하위 업무 집계 1 + 체크리스트 집계 1 + 걸러진 건수 1 + 전체 건수 1로
     * 끝나는지 못 박아 둔다 — 업무가 몇 건이든, 그 아래 하위 업무가 몇 건이든 이 수는 그대로다.
     */
    @Test
    void searchRunsFiveQueriesRegardlessOfRowCount() {
        for (int workIndex = 0; workIndex < 3; workIndex++) {
            Long workId = createWork("업무 " + workIndex, WorkType.EVENT, null, null).workId();
            for (int subWorkIndex = 0; subWorkIndex < 2; subWorkIndex++) {
                createSubWork(workId, "하위 업무 " + workIndex + "-" + subWorkIndex);
            }
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

        WorkSearchResponse response = workService.searchWorks(condition().build());

        assertThat(response.works()).hasSize(3);
        assertThat(response.works())
                .allSatisfy(card -> assertThat(card.subWorkCount()).isEqualTo(2));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(5);
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

    private WorkCreateResponse createWork(
            String title, WorkType workType, OffsetDateTime startAt, OffsetDateTime endAt) {
        return workService.createWork(
                new WorkCreateRequest(title, workType, ownerId, startAt, endAt, null, null),
                registrant);
    }

    /*
     * 등록 시각을 직접 박은 업무. crt_dt는 @CreatedDate라 등록 시점에 서버 시계가 채우므로
     * 정렬을 확인하려면 저장 후 덮어써야 한다. 프로덕션 경로에는 이런 진입점이 없다.
     */
    private Long createWorkRegisteredAt(String title, Instant registeredAt) {
        WorkCreateResponse work = createWork(title, WorkType.EVENT, null, null);
        entityManager.flush();
        entityManager
                .getEntityManager()
                .createQuery(
                        "update OperationEntity o set o.createdAt = :createdAt where o.id = :id")
                .setParameter("createdAt", registeredAt)
                .setParameter("id", work.operationId())
                .executeUpdate();
        entityManager.clear();
        return work.workId();
    }

    private Long createSubWork(Long workId, String title) {
        return subWorkService
                .createSubWork(
                        new SubWorkCreateRequest(
                                workId,
                                title,
                                APPROVAL_FREE_TYPE_ID,
                                ownerId,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null),
                        registrant)
                .subWorkId();
    }

    private void softDelete(Long operationId) {
        entityManager.flush();
        operationRepository.findById(operationId).orElseThrow().softDelete(NOW.toInstant());
        entityManager.flush();
        entityManager.clear();
    }

    // 정상 경로로 완료까지 올린다 (TR-01 → TR-02 → 체크리스트 충족 → TR-03)
    private void complete(Long subWorkId) {
        transition(subWorkId, TransitionAction.START);
        transition(subWorkId, TransitionAction.REQUEST_REVIEW);
        for (Long itemId : checklistItemIds(subWorkId)) {
            checkItem(subWorkId, itemId);
        }
        transition(subWorkId, TransitionAction.APPROVE_COMPLETE);
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

    private void transition(Long subWorkId, TransitionAction action) {
        entityManager.flush();
        entityManager.clear();
        subWorkService.transitionSubWork(
                subWorkId, new SubWorkTransitionRequest(action, null), registrant);
    }

    private WorkSearchResponse search(WorkSearchCondition condition) {
        entityManager.flush();
        entityManager.clear();
        return workService.searchWorks(condition);
    }

    private static List<Long> idsOf(WorkSearchResponse response) {
        return response.works().stream().map(WorkListItemResponse::workId).toList();
    }

    private static ConditionBuilder condition() {
        return new ConditionBuilder();
    }

    /*
     * 쿼리 파라미터가 다섯 개라 테스트마다 null을 늘어놓으면 어느 자리가 무엇인지 읽히지 않는다.
     * 프로덕션 코드에는 빌더를 두지 않는다 — 스프링이 쿼리 파라미터를 그대로 바인딩하므로
     * 필요한 곳이 테스트뿐이다.
     */
    private static final class ConditionBuilder {

        private String workStatus;
        private String workType;
        private Integer size;
        private String cursor;
        private String sort;

        private ConditionBuilder workStatus(String value) {
            this.workStatus = value;
            return this;
        }

        private ConditionBuilder workType(String value) {
            this.workType = value;
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

        private WorkSearchCondition build() {
            return new WorkSearchCondition(workStatus, workType, size, cursor, sort);
        }
    }
}
