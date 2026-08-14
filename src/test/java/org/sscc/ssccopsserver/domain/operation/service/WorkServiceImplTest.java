package org.sscc.ssccopsserver.domain.operation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusHistoryRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.domain.member.service.MemberService;
import org.sscc.ssccopsserver.domain.member.service.MemberServiceImpl;
import org.sscc.ssccopsserver.domain.operation.code.error.OperationErrorCode;
import org.sscc.ssccopsserver.domain.operation.dto.WorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.WorkCreateResponse;
import org.sscc.ssccopsserver.domain.operation.dto.WorkDetailResponse;
import org.sscc.ssccopsserver.domain.operation.dto.WorkSubWorkSummaryResponse;
import org.sscc.ssccopsserver.domain.operation.entity.OperationEntity;
import org.sscc.ssccopsserver.domain.operation.entity.OperationPriority;
import org.sscc.ssccopsserver.domain.operation.entity.OperationType;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkChecklistItemEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkTypeEntity;
import org.sscc.ssccopsserver.domain.operation.entity.WorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.WorkStatus;
import org.sscc.ssccopsserver.domain.operation.entity.WorkType;
import org.sscc.ssccopsserver.domain.operation.repository.OperationRepository;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkChecklistItemRepository;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkRepository;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkTypeRepository;
import org.sscc.ssccopsserver.domain.operation.repository.WorkRepository;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;
import org.sscc.ssccopsserver.global.config.JpaAuditingConfig;
import org.sscc.ssccopsserver.support.MemberFixture;

/*
 * @DataJpaTest는 @Configuration을 걸러내므로 JpaAuditingConfig를 명시적으로 들여온다.
 * 없으면 @CreatedDate가 동작하지 않아 crt_dt NOT NULL 위반으로 저장이 실패한다 —
 * 전체 테스트를 함께 돌릴 때는 다른 @SpringBootTest가 Spring Data의 static 감사 핸들러를
 * 먼저 세팅해 우연히 통과하므로, 이 클래스만 단독 실행할 때 드러난다.
 */
@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Import(JpaAuditingConfig.class)
@ActiveProfiles("test")
class WorkServiceImplTest {

    private static final OffsetDateTime START =
            OffsetDateTime.of(2026, 9, 1, 18, 0, 0, 0, ZoneOffset.ofHours(9));
    private static final OffsetDateTime END = START.plusHours(2);

    // data.sql이 넣는 하위 업무 유형. 3=내부행사(승인 불필요)
    private static final long SUB_WORK_TYPE_ID = 3L;

    private static final Instant DELETED_AT = Instant.parse("2026-08-20T03:00:00Z");

    @Autowired private OperationRepository operationRepository;
    @Autowired private WorkRepository workRepository;
    @Autowired private SubWorkRepository subWorkRepository;
    @Autowired private SubWorkTypeRepository subWorkTypeRepository;
    @Autowired private SubWorkChecklistItemRepository subWorkChecklistItemRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberGradeHistoryRepository memberGradeHistoryRepository;
    @Autowired private MemberStatusHistoryRepository memberStatusHistoryRepository;
    @Autowired private TestEntityManager entityManager;

    private WorkService workService;
    private MemberEntity registrant;
    private MemberEntity owner;
    private Long ownerId;

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
                        Clock.systemDefaultZone());
        workService =
                new WorkServiceImpl(
                        operationRepository,
                        workRepository,
                        subWorkRepository,
                        subWorkChecklistItemRepository,
                        memberService);

        // 등록자와 담당자를 다른 회원으로 둬 둘이 뒤바뀌면 테스트가 깨지게 한다
        registrant = saveMember("20200001", "김도현", "registrant@sscc.org");
        owner = saveMember("20200002", "이서연", "owner@sscc.org");
        ownerId = owner.getId();
    }

    @Test
    void createWorkPersistsOperationAndWork() {
        WorkCreateRequest request =
                new WorkCreateRequest(
                        "2026 신입생 환영회", WorkType.EVENT, ownerId, START, END, null, null);

        WorkCreateResponse response = workService.createWork(request, registrant);

        assertThat(operationRepository.count()).isEqualTo(1);
        assertThat(workRepository.count()).isEqualTo(1);

        OperationEntity operation =
                operationRepository.findById(response.operationId()).orElseThrow();
        assertThat(operation.getOperationType()).isEqualTo(OperationType.WORK);
        assertThat(operation.getTitle()).isEqualTo("2026 신입생 환영회");
        assertThat(operation.getPersonInCharge().getId()).isEqualTo(ownerId);
        assertThat(operation.getBeginAt()).isEqualTo(START.toInstant());
        assertThat(operation.getEndAt()).isEqualTo(END.toInstant());
        assertThat(operation.getDeletedAt()).isNull();
        assertThat(operation.getCreatedAt()).isNotNull();

        // 등록자는 인증 주체에서 오고 담당자와 별개로 기록된다
        assertThat(operation.getRegistrant().getId()).isEqualTo(registrant.getId());
        assertThat(operation.getRegistrant().getId()).isNotEqualTo(ownerId);
        assertThat(response.registrantId()).isEqualTo(registrant.getId());
    }

    @Test
    void createWorkFixesStatusAndProgressRateOnServer() {
        WorkCreateResponse response =
                workService.createWork(
                        new WorkCreateRequest(
                                "정기 총회", WorkType.REGULAR, ownerId, null, null, null, null),
                        registrant);

        assertThat(response.workStatus()).isEqualTo(WorkStatus.PLANNING);
        assertThat(workRepository.findById(response.workId()).orElseThrow().getWorkStatus())
                .isEqualTo(WorkStatus.PLANNING);
        assertThat(response.progressRate()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void createWorkStoresPriorityAndReviewFromScreen() {
        WorkCreateResponse response =
                workService.createWork(
                        new WorkCreateRequest(
                                "동아리 박람회 부스 운영",
                                WorkType.EVENT,
                                ownerId,
                                START,
                                END,
                                OperationPriority.HIGH,
                                "부스 위치 선정이 늦었다"),
                        registrant);

        assertThat(response.priority()).isEqualTo(OperationPriority.HIGH);
        assertThat(response.review()).isEqualTo("부스 위치 선정이 늦었다");

        OperationEntity operation =
                operationRepository.findById(response.operationId()).orElseThrow();
        assertThat(operation.getPriority()).isEqualTo(OperationPriority.HIGH);
        assertThat(workRepository.findById(response.workId()).orElseThrow().getGeneralReview())
                .isEqualTo("부스 위치 선정이 늦었다");
    }

    // 화면 기본값이 '보통'이라 우선순위가 빠진 요청도 NORMAL로 저장돼야 한다
    @Test
    void createWorkWithoutPriorityDefaultsToNormal() {
        WorkCreateResponse response =
                workService.createWork(
                        new WorkCreateRequest(
                                "우선순위 미지정", WorkType.ROUTINE, ownerId, null, null, null, null),
                        registrant);

        assertThat(response.priority()).isEqualTo(OperationPriority.NORMAL);
        assertThat(response.review()).isNull();
    }

    @Test
    void createWorkWithUnknownOwnerIsRejected() {
        WorkCreateRequest request =
                new WorkCreateRequest(
                        "담당자 없는 업무", WorkType.EVENT, ownerId + 999, START, END, null, null);

        assertThatThrownBy(() -> workService.createWork(request, registrant))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.OWNER_NOT_ACTIVE_MEMBER);

        assertThat(operationRepository.count()).isZero();
        assertThat(workRepository.count()).isZero();
    }

    @Test
    void createWorkWithInvertedPeriodIsRejected() {
        WorkCreateRequest request =
                new WorkCreateRequest("기간 역전 업무", WorkType.EVENT, ownerId, END, START, null, null);

        assertThatThrownBy(() -> workService.createWork(request, registrant))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.INVALID_OPERATION_PERIOD);

        assertThat(operationRepository.count()).isZero();
    }

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

    // ---------------------------------------------------------------- OPS-003 상세 조회

    /*
     * 화면이 '공통 속성 · operation'과 '확장 속성 · work' 두 블록으로 나눠 보여주는 값이
     * 한 번의 호출로 모두 채워져야 한다. 응답 자체는 블록으로 나뉘지 않은 평면 구조다.
     */
    @Test
    void getWorkReturnsCommonAndExtendedAttributes() {
        Long workId = createWork("봄MT", "장소 선정 → 대관 → 정산");

        WorkDetailResponse response = detailOf(workId);

        assertThat(response.workId()).isEqualTo(workId);
        assertThat(response.operationId()).isNotNull();
        assertThat(response.operationType()).isEqualTo(OperationType.WORK);
        assertThat(response.title()).isEqualTo("봄MT");
        assertThat(response.workType()).isEqualTo(WorkType.EVENT);
        assertThat(response.workStatus()).isEqualTo(WorkStatus.PLANNING);
        assertThat(response.priority()).isEqualTo(OperationPriority.NORMAL);
        assertThat(response.startAt()).isEqualTo(START);
        assertThat(response.endAt()).isEqualTo(END);
        assertThat(response.generalReview()).isEqualTo("장소 선정 → 대관 → 정산");
        assertThat(response.createdAt()).isNotNull();
        assertThat(response.updatedAt()).isNotNull();

        // 담당자·등록자는 식별자만이 아니라 이름까지 내려야 화면이 그릴 수 있다
        assertThat(response.owner().memberId()).isEqualTo(ownerId);
        assertThat(response.owner().name()).isNotNull();
        assertThat(response.registrant().memberId()).isEqualTo(registrant.getId());
        assertThat(response.registrant().memberId()).isNotEqualTo(ownerId);
    }

    // 하위 업무가 하나도 없으면 진행률은 0이고 목록은 빈 배열이다 (필드를 생략하지 않는다)
    @Test
    void getWorkWithoutSubWorksReturnsZeroProgress() {
        WorkDetailResponse response = detailOf(createWork("하위 업무 없는 업무", null));

        assertThat(response.subWorks()).isEmpty();
        assertThat(response.subWorkCount()).isZero();
        assertThat(response.progressRate()).isEqualByComparingTo("0.00");
    }

    /*
     * 시안 그대로의 상황. 하위 업무 둘 다 검토(= 완료 0건)인데 전체 진행률은 70%다 —
     * 완료 개수 비율이 아니라 하위 업무 진행률의 평균이라는 뜻이다.
     */
    @Test
    void getWorkProgressRateIsAverageOfSubWorkRates() {
        Long workId = createWork("봄MT", null);
        addSubWork(workId, "봄MT 장소 선정", END.toInstant(), 5, 3);
        addSubWork(workId, "봄MT 대관료 집행", END.plusHours(1).toInstant(), 5, 4);

        WorkDetailResponse response = detailOf(workId);

        assertThat(response.subWorks())
                .extracting(
                        WorkSubWorkSummaryResponse::title, WorkSubWorkSummaryResponse::progressRate)
                .containsExactly(
                        tuple("봄MT 장소 선정", new BigDecimal("60.00")),
                        tuple("봄MT 대관료 집행", new BigDecimal("80.00")));
        assertThat(response.subWorkCount()).isEqualTo(2);
        assertThat(response.progressRate()).isEqualByComparingTo("70.00");
    }

    /*
     * 승인이 필요 없는 유형은 완료 점검 항목이 하나도 없을 수 있고, 그래도 완료 전이는
     * 통과한다. 그런 건이 완료되고도 0%로 보이면 안 된다.
     */
    @Test
    void doneSubWorkIsHundredPercentEvenWithoutChecklist() {
        Long workId = createWork("체크리스트 없는 완료", null);
        Long subWorkId = addSubWork(workId, "완료된 하위 업무", END.toInstant(), 0, 0);
        markDone(subWorkId);

        WorkDetailResponse response = detailOf(workId);

        assertThat(response.subWorks().get(0).workStatus()).isEqualTo(WorkStatus.DONE);
        assertThat(response.subWorks().get(0).progressRate()).isEqualByComparingTo("100.00");
        assertThat(response.progressRate()).isEqualByComparingTo("100.00");
    }

    // 체크리스트가 없는 미완료 건은 셀 것이 없으므로 0이다
    @Test
    void incompleteSubWorkWithoutChecklistIsZeroPercent() {
        Long workId = createWork("체크리스트 없는 진행", null);
        addSubWork(workId, "항목 없는 하위 업무", END.toInstant(), 0, 0);

        WorkDetailResponse response = detailOf(workId);

        assertThat(response.subWorks().get(0).progressRate()).isEqualByComparingTo("0.00");
        assertThat(response.progressRate()).isEqualByComparingTo("0.00");
    }

    // 소프트 삭제된 하위 업무는 목록에서도 빠지고 평균의 분모에도 들어가면 안 된다
    @Test
    void deletedSubWorkIsExcludedFromListAndAverage() {
        Long workId = createWork("삭제된 하위 업무가 있는 업무", null);
        addSubWork(workId, "살아있는 하위 업무", END.toInstant(), 5, 3);
        Long deletedId = addSubWork(workId, "삭제된 하위 업무", END.toInstant(), 5, 5);
        softDeleteSubWork(deletedId);

        WorkDetailResponse response = detailOf(workId);

        assertThat(response.subWorks())
                .extracting(WorkSubWorkSummaryResponse::title)
                .containsExactly("살아있는 하위 업무");
        assertThat(response.subWorkCount()).isEqualTo(1);
        // 삭제된 건(100%)이 분모에 들어갔다면 80.00이 됐을 것이다
        assertThat(response.progressRate()).isEqualByComparingTo("60.00");
    }

    /*
     * 시안에 정렬 기준이 없어 서버가 정한다. 마감이 빠른 순이고 마감 없는 건은 뒤로 간다 —
     * NULL 정렬 기본값이 H2와 PostgreSQL에서 갈리므로 쿼리에 nulls last를 명시해 두었다.
     */
    @Test
    void subWorksAreOrderedByDueAtWithNullsLast() {
        Long workId = createWork("정렬 확인용 업무", null);
        addSubWork(workId, "마감 없음", null, 0, 0);
        addSubWork(workId, "늦은 마감", END.plusDays(3).toInstant(), 0, 0);
        addSubWork(workId, "빠른 마감", END.toInstant(), 0, 0);

        assertThat(detailOf(workId).subWorks())
                .extracting(WorkSubWorkSummaryResponse::title)
                .containsExactly("빠른 마감", "늦은 마감", "마감 없음");
    }

    @Test
    void getWorkWithUnknownIdThrowsNotFound() {
        Long workId = createWork("존재 확인용 업무", null);

        assertThatThrownBy(() -> workService.getWork(workId + 999))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.WORK_NOT_FOUND);
    }

    // 소프트 삭제된 업무는 존재를 알려주지 않고 없는 것처럼 404다
    @Test
    void getWorkWithDeletedWorkThrowsNotFound() {
        Long workId = createWork("삭제된 업무", null);
        workRepository.findById(workId).orElseThrow().getOperation().softDelete(DELETED_AT);
        entityManager.flush();
        entityManager.clear();

        assertThatThrownBy(() -> workService.getWork(workId))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.WORK_NOT_FOUND);
    }

    /*
     * 응답의 진행률은 계산값이고 저장 컬럼(work_prgrs_rt)은 그대로여야 한다 (AP-07).
     * 둘이 어긋나는 것은 알고 택한 것이며, 조회가 저장값을 덮어쓰기 시작하면 그때부터
     * GET이 상태를 바꾸는 API가 된다.
     */
    @Test
    void getWorkDoesNotUpdateStoredProgressRate() {
        Long workId = createWork("저장값 확인용 업무", null);
        addSubWork(workId, "진행 중 하위 업무", END.toInstant(), 5, 3);

        assertThat(detailOf(workId).progressRate()).isEqualByComparingTo("60.00");

        entityManager.flush();
        entityManager.clear();
        assertThat(workRepository.findById(workId).orElseThrow().getProgressRate())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    /*
     * 하위 업무마다 체크리스트를 세면 그대로 N+1이 된다 (DB-13). 업무 1 + 하위 업무 목록 1 +
     * 체크리스트 집계 1로 끝나는지 못 박아 둔다 — 하위 업무가 몇 건이든 이 수는 그대로다.
     */
    @Test
    void getWorkRunsThreeQueries() {
        Long workId = createWork("쿼리 수 확인용 업무", null);
        addSubWork(workId, "하위 업무 1", END.toInstant(), 5, 3);
        addSubWork(workId, "하위 업무 2", END.plusHours(1).toInstant(), 5, 4);
        addSubWork(workId, "하위 업무 3", END.plusHours(2).toInstant(), 5, 5);
        entityManager.flush();
        entityManager.clear();

        Statistics statistics =
                entityManager
                        .getEntityManager()
                        .getEntityManagerFactory()
                        .unwrap(SessionFactory.class)
                        .getStatistics();
        statistics.clear();

        workService.getWork(workId);

        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
    }

    private Long createWork(String title, String review) {
        return workService
                .createWork(
                        new WorkCreateRequest(
                                title, WorkType.EVENT, ownerId, START, END, null, review),
                        registrant)
                .workId();
    }

    /*
     * 하위 업무를 서비스가 아니라 저장소로 직접 만든다. 등록 API(OPS-007)를 쓰면 체크리스트
     * 개수가 유형에 묶여 있어 60%·80% 같은 비율을 만들 수 없기 때문이다.
     */
    private Long addSubWork(
            Long workId, String title, Instant dueAt, int checklistSize, int completedSize) {
        WorkEntity work = workRepository.findById(workId).orElseThrow();
        OperationEntity operation =
                operationRepository.save(
                        OperationEntity.createForSubWork(
                                title,
                                registrant,
                                owner,
                                START.toInstant(),
                                END.toInstant(),
                                OperationPriority.NORMAL));
        SubWorkTypeEntity subWorkType =
                subWorkTypeRepository.findById(SUB_WORK_TYPE_ID).orElseThrow();
        SubWorkEntity subWork =
                subWorkRepository.save(
                        SubWorkEntity.create(
                                work, operation, subWorkType, title, null, null, dueAt));

        for (int order = 1; order <= checklistSize; order++) {
            subWorkChecklistItemRepository.save(
                    SubWorkChecklistItemEntity.create(subWork, "점검 항목 " + order, order));
        }
        if (completedSize > 0) {
            completeChecklistItems(subWork.getId(), completedSize);
        }
        return subWork.getId();
    }

    /*
     * 앞에서부터 지정한 개수만큼 체크한다. 체크 API(OPS-012)가 아직 없어 직접 갱신한다 —
     * 그 API가 붙으면 이 헬퍼는 API 호출로 바뀐다.
     */
    private void completeChecklistItems(Long subWorkId, int completedSize) {
        entityManager.flush();
        entityManager
                .getEntityManager()
                .createQuery(
                        "update SubWorkChecklistItemEntity i set i.completed = true"
                                + " where i.subWork.id = :id and i.sortOrder <= :completedSize")
                .setParameter("id", subWorkId)
                .setParameter("completedSize", completedSize)
                .executeUpdate();
        entityManager.clear();
    }

    // 상태 전이(OPS-010)를 거치지 않고 완료로 만든다. 여기서 검증할 것은 전이 규칙이 아니다
    private void markDone(Long subWorkId) {
        entityManager.flush();
        entityManager
                .getEntityManager()
                .createQuery("update SubWorkEntity s set s.workStatus = :status where s.id = :id")
                .setParameter("status", WorkStatus.DONE)
                .setParameter("id", subWorkId)
                .executeUpdate();
        entityManager.clear();
    }

    // 하위 업무의 소프트 삭제 여부는 sub_work가 아니라 부모 oper가 갖는다
    private void softDeleteSubWork(Long subWorkId) {
        subWorkRepository.findById(subWorkId).orElseThrow().getOperation().softDelete(DELETED_AT);
        entityManager.flush();
        entityManager.clear();
    }

    private WorkDetailResponse detailOf(Long workId) {
        entityManager.flush();
        entityManager.clear();
        return workService.getWork(workId);
    }
}
