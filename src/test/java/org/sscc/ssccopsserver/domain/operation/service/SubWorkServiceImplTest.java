package org.sscc.ssccopsserver.domain.operation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
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
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.domain.member.service.MemberService;
import org.sscc.ssccopsserver.domain.member.service.MemberServiceImpl;
import org.sscc.ssccopsserver.domain.operation.code.error.OperationErrorCode;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkChecklistItemResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkCreateResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkDetailResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkTransitionRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkTransitionResponse;
import org.sscc.ssccopsserver.domain.operation.dto.WorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.entity.ApprovalStatus;
import org.sscc.ssccopsserver.domain.operation.entity.OperationEntity;
import org.sscc.ssccopsserver.domain.operation.entity.OperationPriority;
import org.sscc.ssccopsserver.domain.operation.entity.OperationType;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkApprovalEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkChecklistItemEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkRejectionEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkStatusHistoryEntity;
import org.sscc.ssccopsserver.domain.operation.entity.TransitionAction;
import org.sscc.ssccopsserver.domain.operation.entity.WorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.WorkStatus;
import org.sscc.ssccopsserver.domain.operation.entity.WorkType;
import org.sscc.ssccopsserver.domain.operation.repository.OperationRepository;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkApprovalRepository;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkChecklistItemRepository;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkRejectionRepository;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkRepository;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkStatusHistoryRepository;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkTypeRepository;
import org.sscc.ssccopsserver.domain.operation.repository.WorkRepository;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;
import org.sscc.ssccopsserver.global.config.JpaAuditingConfig;

/*
 * @DataJpaTest는 @Configuration을 걸러내므로 JpaAuditingConfig를 명시적으로 들여온다.
 * 없으면 @CreatedDate가 동작하지 않아 crt_dt NOT NULL 위반으로 저장이 실패한다 —
 * 전체 테스트를 함께 돌릴 때는 다른 @SpringBootTest가 Spring Data의 static 감사 핸들러를
 * 먼저 세팅해 우연히 통과하므로, 이 클래스만 단독 실행할 때 드러난다.
 */
@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Import(JpaAuditingConfig.class)
@ActiveProfiles("test")
class SubWorkServiceImplTest {

    // data.sql이 넣는 유형. 1=예산지출(승인 필요), 3=내부행사(승인 불필요)
    private static final long APPROVAL_NEEDED_TYPE_ID = 1L;
    private static final long APPROVAL_FREE_TYPE_ID = 3L;

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    private static final OffsetDateTime START = OffsetDateTime.of(2026, 9, 1, 18, 0, 0, 0, KST);
    private static final OffsetDateTime END = START.plusHours(2);

    /*
     * 마감 경과 판정의 기준 시각. 고정하지 않으면 START·END가 과거가 되는 날부터
     * isDelayed 검증이 조용히 뒤집힌다. START·END(9월 1일)보다는 앞선 시각이다.
     */
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 8, 20, 12, 0, 0, 0, KST);

    private static final Clock FIXED_CLOCK = Clock.fixed(NOW.toInstant(), KST);

    @Autowired private OperationRepository operationRepository;
    @Autowired private WorkRepository workRepository;
    @Autowired private SubWorkRepository subWorkRepository;
    @Autowired private SubWorkTypeRepository subWorkTypeRepository;
    @Autowired private SubWorkChecklistItemRepository subWorkChecklistItemRepository;
    @Autowired private SubWorkStatusHistoryRepository subWorkStatusHistoryRepository;
    @Autowired private SubWorkApprovalRepository subWorkApprovalRepository;
    @Autowired private SubWorkRejectionRepository subWorkRejectionRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private TestEntityManager entityManager;

    private SubWorkService subWorkService;
    private MemberEntity registrant;
    private Long ownerId;
    private Long parentWorkId;

    @BeforeEach
    void setUp() {
        MemberService memberService =
                new MemberServiceImpl(
                        memberRepository, memberGradeRepository, memberStatusRepository);
        WorkService workService =
                new WorkServiceImpl(
                        operationRepository,
                        workRepository,
                        subWorkRepository,
                        subWorkChecklistItemRepository,
                        memberService);
        subWorkService =
                new SubWorkServiceImpl(
                        operationRepository,
                        workRepository,
                        subWorkRepository,
                        subWorkTypeRepository,
                        subWorkChecklistItemRepository,
                        subWorkStatusHistoryRepository,
                        subWorkApprovalRepository,
                        subWorkRejectionRepository,
                        memberService,
                        FIXED_CLOCK);

        // 등록자와 담당자를 다른 회원으로 둬 둘이 뒤바뀌면 테스트가 깨지게 한다
        registrant =
                memberService.findOrProvisionByAuthUserId(UUID.randomUUID(), "registrant@sscc.org");
        MemberEntity owner =
                memberService.findOrProvisionByAuthUserId(UUID.randomUUID(), "owner@sscc.org");
        ownerId = owner.getId();
        parentWorkId =
                workService
                        .createWork(
                                new WorkCreateRequest(
                                        "2026 동아리 박람회",
                                        WorkType.EVENT,
                                        ownerId,
                                        START,
                                        END,
                                        null,
                                        null),
                                registrant)
                        .workId();
    }

    private SubWorkCreateRequest request(long subWorkTypeId) {
        return new SubWorkCreateRequest(
                parentWorkId,
                "부스 배치도 확정",
                subWorkTypeId,
                ownerId,
                START,
                END,
                END,
                OperationPriority.HIGH,
                "박람회 부스 위치와 동선을 확정한다",
                "https://docs.example.com/booth");
    }

    @Test
    void createSubWorkPersistsOperationAndSubWork() {
        SubWorkCreateResponse response =
                subWorkService.createSubWork(request(APPROVAL_FREE_TYPE_ID), registrant);

        SubWorkEntity subWork = subWorkRepository.findById(response.subWorkId()).orElseThrow();
        assertThat(subWork.getWork().getId()).isEqualTo(parentWorkId);
        assertThat(subWork.getTitle()).isEqualTo("부스 배치도 확정");
        assertThat(subWork.getContent()).isEqualTo("박람회 부스 위치와 동선을 확정한다");
        assertThat(subWork.getExternalLink()).isEqualTo("https://docs.example.com/booth");
        assertThat(subWork.getDueAt()).isEqualTo(END.toInstant());

        // 하위 업무도 자기 oper를 갖고, 제목·기간·담당자·우선순위는 거기에 저장된다
        OperationEntity operation =
                operationRepository.findById(response.operationId()).orElseThrow();
        assertThat(operation.getOperationType()).isEqualTo(OperationType.SUB_WORK);
        assertThat(operation.getTitle()).isEqualTo("부스 배치도 확정");
        assertThat(operation.getPersonInCharge().getId()).isEqualTo(ownerId);
        assertThat(operation.getBeginAt()).isEqualTo(START.toInstant());
        assertThat(operation.getEndAt()).isEqualTo(END.toInstant());
        assertThat(operation.getPriority()).isEqualTo(OperationPriority.HIGH);

        // 등록자는 인증 주체에서 오고 담당자와 별개로 기록된다.
        // 인증 주체는 필터에서 로드돼 준영속 상태이므로, FK가 실제 컬럼에 써졌는지는
        // 영속성 컨텍스트를 비우고 DB에서 다시 읽어 확인한다
        entityManager.flush();
        entityManager.clear();

        OperationEntity reloaded =
                operationRepository.findById(response.operationId()).orElseThrow();
        assertThat(reloaded.getRegistrant().getId()).isEqualTo(registrant.getId());
        assertThat(reloaded.getRegistrant().getId()).isNotEqualTo(ownerId);
        assertThat(response.registrantId()).isEqualTo(registrant.getId());
    }

    // 화면 안내: "완료 체크리스트 4항목이 기본 생성되며, 등록 직후 단계는 기획입니다"
    @Test
    void createSubWorkCopiesTypeChecklistAndStartsInPlanning() {
        SubWorkCreateResponse response =
                subWorkService.createSubWork(request(APPROVAL_FREE_TYPE_ID), registrant);

        assertThat(response.workStatus()).isEqualTo(WorkStatus.PLANNING);
        assertThat(response.isDelayed()).isFalse();
        assertThat(response.checklist()).hasSize(4);
        assertThat(response.checklist())
                .extracting(SubWorkChecklistItemResponse::sortOrder)
                .containsExactly(1, 2, 3, 4);
        assertThat(response.checklist())
                .allSatisfy(item -> assertThat(item.isCompleted()).isFalse());
        assertThat(response.checklist().get(0).article()).isEqualTo("일시·장소 확정");
        assertThat(subWorkChecklistItemRepository.count()).isEqualTo(4);
    }

    @Test
    void createSubWorkDerivesApprovalStatusFromType() {
        assertThat(
                        subWorkService
                                .createSubWork(request(APPROVAL_NEEDED_TYPE_ID), registrant)
                                .approvalStatus())
                .isEqualTo(ApprovalStatus.PENDING);
        assertThat(
                        subWorkService
                                .createSubWork(request(APPROVAL_FREE_TYPE_ID), registrant)
                                .approvalStatus())
                .isEqualTo(ApprovalStatus.NOT_REQUIRED);
    }

    // 상위 업무 진행률은 하위 업무 완료율에서 나온다. 기획 상태 하위 업무만 있으면 0이다
    @Test
    void createSubWorkRecalculatesParentProgressRate() {
        subWorkService.createSubWork(request(APPROVAL_FREE_TYPE_ID), registrant);
        subWorkService.createSubWork(request(APPROVAL_FREE_TYPE_ID), registrant);

        WorkEntity parentWork = workRepository.findById(parentWorkId).orElseThrow();
        assertThat(parentWork.getProgressRate()).isEqualByComparingTo(BigDecimal.ZERO);

        assertThat(subWorkRepository.count()).isEqualTo(2);
    }

    @Test
    void createSubWorkWithUnknownParentWorkIsRejected() {
        SubWorkCreateRequest request =
                new SubWorkCreateRequest(
                        parentWorkId + 999,
                        "상위 업무 없는 하위 업무",
                        APPROVAL_FREE_TYPE_ID,
                        ownerId,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);

        assertThatThrownBy(() -> subWorkService.createSubWork(request, registrant))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.WORK_NOT_FOUND);

        assertThat(subWorkRepository.count()).isZero();
    }

    @Test
    void createSubWorkWithUnknownTypeIsRejected() {
        SubWorkCreateRequest request =
                new SubWorkCreateRequest(
                        parentWorkId,
                        "유형 없는 하위 업무",
                        999L,
                        ownerId,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);

        assertThatThrownBy(() -> subWorkService.createSubWork(request, registrant))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.SUB_WORK_TYPE_NOT_FOUND);

        assertThat(subWorkRepository.count()).isZero();
    }

    @Test
    void createSubWorkWithUnknownOwnerIsRejected() {
        SubWorkCreateRequest request =
                new SubWorkCreateRequest(
                        parentWorkId,
                        "담당자 없는 하위 업무",
                        APPROVAL_FREE_TYPE_ID,
                        ownerId + 999,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);

        assertThatThrownBy(() -> subWorkService.createSubWork(request, registrant))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.OWNER_NOT_ACTIVE_MEMBER);

        assertThat(subWorkRepository.count()).isZero();
    }

    @Test
    void createSubWorkWithInvertedPeriodIsRejected() {
        SubWorkCreateRequest request =
                new SubWorkCreateRequest(
                        parentWorkId,
                        "기간 역전 하위 업무",
                        APPROVAL_FREE_TYPE_ID,
                        ownerId,
                        END,
                        START,
                        null,
                        null,
                        null,
                        null);

        assertThatThrownBy(() -> subWorkService.createSubWork(request, registrant))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.INVALID_OPERATION_PERIOD);

        assertThat(subWorkRepository.count()).isZero();
    }

    // 상세 화면 한 장이 필요로 하는 값이 한 번의 조회로 다 나오는지 (OPS-009)
    @Test
    void getSubWorkReturnsDetailForScreen() {
        Long subWorkId =
                subWorkService
                        .createSubWork(request(APPROVAL_FREE_TYPE_ID), registrant)
                        .subWorkId();
        entityManager.flush();
        entityManager.clear();

        SubWorkDetailResponse detail = subWorkService.getSubWork(subWorkId);

        assertThat(detail.subWorkId()).isEqualTo(subWorkId);
        assertThat(detail.workId()).isEqualTo(parentWorkId);
        assertThat(detail.operationType()).isEqualTo(OperationType.SUB_WORK);
        assertThat(detail.title()).isEqualTo("부스 배치도 확정");
        assertThat(detail.subWorkTypeName()).isEqualTo("내부행사");
        assertThat(detail.workStatus()).isEqualTo(WorkStatus.PLANNING);
        assertThat(detail.priority()).isEqualTo(OperationPriority.HIGH);
        assertThat(detail.content()).isEqualTo("박람회 부스 위치와 동선을 확정한다");
        assertThat(detail.externalLink()).isEqualTo("https://docs.example.com/booth");
        assertThat(detail.startAt().toInstant()).isEqualTo(START.toInstant());
        assertThat(detail.dueAt().toInstant()).isEqualTo(END.toInstant());
        assertThat(detail.completedAt()).isNull();

        // 담당자·등록자는 식별자가 아니라 이름까지 나와야 화면을 그릴 수 있다
        assertThat(detail.owner().memberId()).isEqualTo(ownerId);
        assertThat(detail.owner().name()).isNotBlank();
        assertThat(detail.registrant().memberId()).isEqualTo(registrant.getId());

        // 협업자 배정(sub_work_pic_altmnt)은 아직 없어 항상 비어 있다 — 필드는 유지한다
        assertThat(detail.collaborators()).isEmpty();
    }

    @Test
    void getSubWorkReturnsChecklistInOrderWithSummary() {
        Long subWorkId =
                subWorkService
                        .createSubWork(request(APPROVAL_FREE_TYPE_ID), registrant)
                        .subWorkId();
        // 첫 항목만 완료 처리한다. 체크 API(OPS-012)가 아직 없어 엔티티를 직접 저장한다
        SubWorkChecklistItemEntity firstItem =
                subWorkChecklistItemRepository
                        .findBySubWorkOrderBySortOrderAsc(
                                subWorkRepository.findById(subWorkId).orElseThrow())
                        .get(0);
        entityManager
                .getEntityManager()
                .createQuery(
                        "update SubWorkChecklistItemEntity i set i.completed = true where i.id ="
                                + " :id")
                .setParameter("id", firstItem.getId())
                .executeUpdate();
        entityManager.clear();

        SubWorkDetailResponse detail = subWorkService.getSubWork(subWorkId);

        assertThat(detail.checklist())
                .extracting(SubWorkChecklistItemResponse::sortOrder)
                .containsExactly(1, 2, 3, 4);
        assertThat(detail.checklist().get(0).isCompleted()).isTrue();
        assertThat(detail.checklistSummary().completedCount()).isEqualTo(1);
        assertThat(detail.checklistSummary().totalCount()).isEqualTo(4);
    }

    // 화면의 "완료 전환은 회장·국장 승인이 필요합니다" 안내는 유형의 승인 정책에서 나온다
    @Test
    void getSubWorkExposesTypeApprovalPolicy() {
        Long approvalNeededId =
                subWorkService
                        .createSubWork(request(APPROVAL_NEEDED_TYPE_ID), registrant)
                        .subWorkId();
        Long approvalFreeId =
                subWorkService
                        .createSubWork(request(APPROVAL_FREE_TYPE_ID), registrant)
                        .subWorkId();

        SubWorkDetailResponse approvalNeeded = subWorkService.getSubWork(approvalNeededId);
        assertThat(approvalNeeded.approvalRequired()).isTrue();
        assertThat(approvalNeeded.approvalStatus()).isEqualTo(ApprovalStatus.PENDING);

        SubWorkDetailResponse approvalFree = subWorkService.getSubWork(approvalFreeId);
        assertThat(approvalFree.approvalRequired()).isFalse();
        assertThat(approvalFree.approvalStatus()).isEqualTo(ApprovalStatus.NOT_REQUIRED);
    }

    @Test
    void getSubWorkWithUnknownIdIsRejected() {
        assertThatThrownBy(() -> subWorkService.getSubWork(999L))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.SUB_WORK_NOT_FOUND);
    }

    // 소프트 삭제된 건은 존재하지 않는 것처럼 404다. 409로 나누면 존재 사실이 새어나간다
    @Test
    void getSoftDeletedSubWorkIsRejected() {
        SubWorkCreateResponse created =
                subWorkService.createSubWork(request(APPROVAL_FREE_TYPE_ID), registrant);
        operationRepository
                .findById(created.operationId())
                .orElseThrow()
                .softDelete(NOW.toInstant());
        entityManager.flush();
        entityManager.clear();

        assertThatThrownBy(() -> subWorkService.getSubWork(created.subWorkId()))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.SUB_WORK_NOT_FOUND);
    }

    @Test
    void isDelayedIsFalseWhenNoDueDate() {
        assertThat(detailOf(subWorkWithDueAt(null)).isDelayed()).isFalse();
    }

    @Test
    void isDelayedIsFalseBeforeDueDate() {
        assertThat(detailOf(subWorkWithDueAt(NOW.plusDays(1))).isDelayed()).isFalse();
    }

    @Test
    void isDelayedIsTrueAfterDueDateWhileUnfinished() {
        assertThat(detailOf(subWorkWithDueAt(NOW.minusDays(1))).isDelayed()).isTrue();
    }

    // 늦게 끝났더라도 완료된 건은 지연이 아니다 — 화면은 지금 손봐야 하는 건만 표시한다
    @Test
    void isDelayedIsFalseAfterDueDateWhenDone() {
        Long subWorkId = subWorkWithDueAt(NOW.minusDays(1));
        // 상태 전이 API(OPS-010)가 아직 없어 완료 상태를 직접 만든다
        entityManager
                .getEntityManager()
                .createQuery("update SubWorkEntity s set s.workStatus = :status where s.id = :id")
                .setParameter("status", WorkStatus.DONE)
                .setParameter("id", subWorkId)
                .executeUpdate();
        entityManager.clear();

        assertThat(detailOf(subWorkId).isDelayed()).isFalse();
    }

    /*
     * 상세 응답이 담당자·등록자 이름과 유형명·상위 업무를 모두 쓰므로, 연관을 지연 로딩에
     * 맡기면 응답을 조립하는 동안 쿼리가 하나씩 더 나간다 (DB-13). 하위 업무 + 체크리스트
     * 2회로 끝나는지 못 박아 둔다 — 연관이 늘어도 EntityGraph에 넣으면 이 수는 유지된다.
     */
    @Test
    void getSubWorkRunsTwoQueries() {
        Long subWorkId =
                subWorkService
                        .createSubWork(request(APPROVAL_FREE_TYPE_ID), registrant)
                        .subWorkId();
        entityManager.flush();
        entityManager.clear();

        Statistics statistics =
                entityManager
                        .getEntityManager()
                        .getEntityManagerFactory()
                        .unwrap(SessionFactory.class)
                        .getStatistics();
        statistics.clear();

        subWorkService.getSubWork(subWorkId);

        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }

    // TR-01 착수. 승인 상태는 검토요청 전까지 등록 시점 값 그대로다
    @Test
    void startMovesPlanningToInProgress() {
        Long subWorkId = createSubWork(APPROVAL_NEEDED_TYPE_ID);

        SubWorkTransitionResponse response = transition(subWorkId, TransitionAction.START, null);

        assertThat(response.previousWorkStatus()).isEqualTo(WorkStatus.PLANNING);
        assertThat(response.workStatus()).isEqualTo(WorkStatus.IN_PROGRESS);
        assertThat(response.approvalStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(response.completedAt()).isNull();
    }

    // TR-02 검토요청. 승인이 필요한 유형은 여기서 승인 대기가 된다
    @Test
    void requestReviewPutsApprovalNeededTypeIntoPending() {
        Long subWorkId = subWorkInReview(APPROVAL_NEEDED_TYPE_ID);

        SubWorkDetailResponse detail = detailOf(subWorkId);

        assertThat(detail.workStatus()).isEqualTo(WorkStatus.REVIEW);
        assertThat(detail.approvalStatus()).isEqualTo(ApprovalStatus.PENDING);
    }

    // 승인이 필요 없는 유형(REQ-016 저위험 면제)은 검토 단계에서도 승인 상태가 불필요 그대로다
    @Test
    void requestReviewKeepsApprovalFreeTypeNotRequired() {
        Long subWorkId = subWorkInReview(APPROVAL_FREE_TYPE_ID);

        assertThat(detailOf(subWorkId).approvalStatus()).isEqualTo(ApprovalStatus.NOT_REQUIRED);
    }

    /*
     * TR-03 승인·완료. 승인과 완료가 한 단계라 승인 상태와 업무 상태가 함께 바뀌고,
     * 완료 일시와 상위 업무 진행률도 이 전이에서 갱신된다.
     */
    @Test
    void approveCompleteMarksDoneAndRecordsCompletedAt() {
        Long subWorkId = subWorkInReview(APPROVAL_NEEDED_TYPE_ID);
        completeChecklist(subWorkId);

        SubWorkTransitionResponse response =
                transition(subWorkId, TransitionAction.APPROVE_COMPLETE, null);

        assertThat(response.previousApprovalStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(response.workStatus()).isEqualTo(WorkStatus.DONE);
        assertThat(response.approvalStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(response.completedAt()).isEqualTo(NOW);
        assertThat(response.parentWorkProgressRate())
                .isEqualByComparingTo(new BigDecimal("100.00"));
    }

    // 승인이 필요 없는 유형은 승인 상태를 승인으로 바꾸지 않는다 — 승인 절차를 아예 타지 않는다
    @Test
    void approveCompleteKeepsApprovalFreeTypeNotRequired() {
        Long subWorkId = subWorkInReview(APPROVAL_FREE_TYPE_ID);
        completeChecklist(subWorkId);

        SubWorkTransitionResponse response =
                transition(subWorkId, TransitionAction.APPROVE_COMPLETE, null);

        assertThat(response.workStatus()).isEqualTo(WorkStatus.DONE);
        assertThat(response.approvalStatus()).isEqualTo(ApprovalStatus.NOT_REQUIRED);
    }

    // 완료 체크리스트를 다 채우지 않은 건은 완료되지 않는다 (REQ-021)
    @Test
    void approveCompleteWithUnfinishedChecklistIsRejected() {
        Long subWorkId = subWorkInReview(APPROVAL_NEEDED_TYPE_ID);

        assertThatThrownBy(() -> transition(subWorkId, TransitionAction.APPROVE_COMPLETE, null))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.COMPLETION_CRITERIA_UNMET);
        assertThat(detailOf(subWorkId).workStatus()).isEqualTo(WorkStatus.REVIEW);
    }

    // TR-04 반려. 상태가 진행으로 회귀하고 승인 상태는 반려로 남는다
    @Test
    void rejectReturnsToInProgress() {
        Long subWorkId = subWorkInReview(APPROVAL_NEEDED_TYPE_ID);

        SubWorkTransitionResponse response =
                transition(subWorkId, TransitionAction.REJECT, "예산안 대비 초과");

        assertThat(response.workStatus()).isEqualTo(WorkStatus.IN_PROGRESS);
        assertThat(response.approvalStatus()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(response.completedAt()).isNull();
    }

    // 반려는 사유 없이 성립하지 않는다 (VR-O06). 400이 아니라 422다
    @Test
    void rejectWithoutReasonIsRejected() {
        Long subWorkId = subWorkInReview(APPROVAL_NEEDED_TYPE_ID);

        assertThatThrownBy(() -> transition(subWorkId, TransitionAction.REJECT, "   "))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.REASON_REQUIRED);
        assertThat(detailOf(subWorkId).workStatus()).isEqualTo(WorkStatus.REVIEW);
    }

    /*
     * 반려된 건이 보완 후 다시 올라오면 대기가 아니라 재승인필요다 — 승인함(OPS-017)에서
     * 처음 올라온 건과 구분되어야 한다.
     */
    @Test
    void reReviewAfterRejectionRequiresReapproval() {
        Long subWorkId = subWorkInReview(APPROVAL_NEEDED_TYPE_ID);
        transition(subWorkId, TransitionAction.REJECT, "예산안 대비 초과");

        SubWorkTransitionResponse response =
                transition(subWorkId, TransitionAction.REQUEST_REVIEW, null);

        assertThat(response.previousApprovalStatus()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(response.approvalStatus()).isEqualTo(ApprovalStatus.REAPPROVAL_REQUIRED);
    }

    // 전이표에 없는 조합은 전부 차단한다 (BR-O03·VR-O04). 기획 상태에서 완료로 건너뛸 수 없다
    @Test
    void transitionOutsideTransitionTableIsRejected() {
        Long subWorkId = createSubWork(APPROVAL_NEEDED_TYPE_ID);

        assertThatThrownBy(() -> transition(subWorkId, TransitionAction.APPROVE_COMPLETE, null))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.TRANSITION_NOT_ALLOWED);
    }

    // TR-X1 — 완료된 건을 진행으로 되돌리는 경로는 없다
    @Test
    void completedSubWorkCannotBeReopened() {
        Long subWorkId = subWorkInReview(APPROVAL_FREE_TYPE_ID);
        completeChecklist(subWorkId);
        transition(subWorkId, TransitionAction.APPROVE_COMPLETE, null);

        assertThatThrownBy(() -> transition(subWorkId, TransitionAction.START, null))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.TRANSITION_NOT_ALLOWED);
    }

    // 전이마다 전/후 상태와 수행자가 이력에 남는다. 남지 않으면 나중에 소급할 수 없다
    @Test
    void transitionRecordsStatusHistory() {
        Long subWorkId = createSubWork(APPROVAL_NEEDED_TYPE_ID);
        transition(subWorkId, TransitionAction.START, null);
        transition(subWorkId, TransitionAction.REQUEST_REVIEW, null);
        transition(subWorkId, TransitionAction.REJECT, "견적서 재첨부 필요");

        SubWorkEntity subWork = subWorkRepository.findById(subWorkId).orElseThrow();
        List<SubWorkStatusHistoryEntity> histories =
                subWorkStatusHistoryRepository.findBySubWorkOrderByChangedAtAsc(subWork);

        assertThat(histories).hasSize(3);
        assertThat(histories)
                .extracting(
                        SubWorkStatusHistoryEntity::getPreviousWorkStatus,
                        SubWorkStatusHistoryEntity::getNextWorkStatus)
                .containsExactly(
                        tuple(WorkStatus.PLANNING, WorkStatus.IN_PROGRESS),
                        tuple(WorkStatus.IN_PROGRESS, WorkStatus.REVIEW),
                        tuple(WorkStatus.REVIEW, WorkStatus.IN_PROGRESS));
        assertThat(histories.get(2).getChangeReason()).isEqualTo("견적서 재첨부 필요");
        assertThat(histories.get(2).getPerformer().getId()).isEqualTo(registrant.getId());
        assertThat(histories.get(2).getChangedAt()).isEqualTo(NOW.toInstant());
    }

    // 반려는 사유·반려자와 함께 반려 테이블에도 남고, 어느 전이에서 나왔는지 이력에 이어진다
    @Test
    void rejectRecordsRejectionLinkedToHistory() {
        Long subWorkId = subWorkInReview(APPROVAL_NEEDED_TYPE_ID);
        transition(subWorkId, TransitionAction.REJECT, "견적서 재첨부 필요");

        SubWorkEntity subWork = subWorkRepository.findById(subWorkId).orElseThrow();
        List<SubWorkRejectionEntity> rejections =
                subWorkRejectionRepository.findBySubWorkOrderByRejectedAtAsc(subWork);

        assertThat(rejections).hasSize(1);
        assertThat(rejections.get(0).getReason()).isEqualTo("견적서 재첨부 필요");
        assertThat(rejections.get(0).getRejector().getId()).isEqualTo(registrant.getId());
        assertThat(rejections.get(0).getRejectedAt()).isEqualTo(NOW.toInstant());
        assertThat(rejections.get(0).getStatusHistory().getNextWorkStatus())
                .isEqualTo(WorkStatus.IN_PROGRESS);
    }

    /*
     * 자가 승인은 막지 않고 표시만 한다 (POL-006). 판정 기준은 담당자가 아니라 등록자다 —
     * 이 테스트의 등록자와 승인자는 같은 사람이다.
     */
    @Test
    void approveCompleteMarksSelfApprovalWhenApproverIsRegistrant() {
        Long subWorkId = subWorkInReview(APPROVAL_NEEDED_TYPE_ID);
        completeChecklist(subWorkId);

        SubWorkTransitionResponse response =
                transition(subWorkId, TransitionAction.APPROVE_COMPLETE, null);

        SubWorkEntity subWork = subWorkRepository.findById(subWorkId).orElseThrow();
        List<SubWorkApprovalEntity> approvals =
                subWorkApprovalRepository.findBySubWorkOrderByApprovedAtAsc(subWork);

        assertThat(response.isSelfApproval()).isTrue();
        assertThat(approvals).hasSize(1);
        assertThat(approvals.get(0).isRegistrantApproval()).isTrue();
        assertThat(approvals.get(0).getApprovedAt()).isEqualTo(NOW.toInstant());
    }

    @Test
    void approveCompleteByAnotherMemberIsNotSelfApproval() {
        Long subWorkId = subWorkInReview(APPROVAL_NEEDED_TYPE_ID);
        completeChecklist(subWorkId);
        MemberEntity approver = memberRepository.findById(ownerId).orElseThrow();

        SubWorkTransitionResponse response =
                subWorkService.transitionSubWork(
                        subWorkId,
                        new SubWorkTransitionRequest(TransitionAction.APPROVE_COMPLETE, null),
                        approver);

        assertThat(response.isSelfApproval()).isFalse();
    }

    @Test
    void transitionOnUnknownSubWorkIsRejected() {
        assertThatThrownBy(() -> transition(999L, TransitionAction.START, null))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.SUB_WORK_NOT_FOUND);
    }

    // 소프트 삭제된 건은 조회와 마찬가지로 존재하지 않는 것처럼 다룬다
    @Test
    void transitionOnSoftDeletedSubWorkIsRejected() {
        SubWorkCreateResponse created =
                subWorkService.createSubWork(request(APPROVAL_FREE_TYPE_ID), registrant);
        operationRepository
                .findById(created.operationId())
                .orElseThrow()
                .softDelete(NOW.toInstant());
        entityManager.flush();
        entityManager.clear();

        assertThatThrownBy(() -> transition(created.subWorkId(), TransitionAction.START, null))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.SUB_WORK_NOT_FOUND);
    }

    private Long createSubWork(long subWorkTypeId) {
        return subWorkService.createSubWork(request(subWorkTypeId), registrant).subWorkId();
    }

    private SubWorkTransitionResponse transition(
            Long subWorkId, TransitionAction action, String reason) {
        entityManager.flush();
        entityManager.clear();
        return subWorkService.transitionSubWork(
                subWorkId, new SubWorkTransitionRequest(action, reason), registrant);
    }

    // 검토 단계까지 정상 경로(TR-01 → TR-02)로 올려둔다
    private Long subWorkInReview(long subWorkTypeId) {
        Long subWorkId = createSubWork(subWorkTypeId);
        transition(subWorkId, TransitionAction.START, null);
        transition(subWorkId, TransitionAction.REQUEST_REVIEW, null);
        return subWorkId;
    }

    /*
     * 체크리스트를 전부 체크한 상태로 만든다. 체크 API(OPS-012)가 아직 없어 직접 갱신한다 —
     * 그 API가 붙으면 이 헬퍼는 API 호출로 바뀐다.
     */
    private void completeChecklist(Long subWorkId) {
        entityManager.flush();
        entityManager
                .getEntityManager()
                .createQuery(
                        "update SubWorkChecklistItemEntity i set i.completed = true"
                                + " where i.subWork.id = :id")
                .setParameter("id", subWorkId)
                .executeUpdate();
        entityManager.clear();
    }

    private Long subWorkWithDueAt(OffsetDateTime dueAt) {
        return subWorkService
                .createSubWork(
                        new SubWorkCreateRequest(
                                parentWorkId,
                                "마감 판정용 하위 업무",
                                APPROVAL_FREE_TYPE_ID,
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

    private SubWorkDetailResponse detailOf(Long subWorkId) {
        entityManager.flush();
        entityManager.clear();
        return subWorkService.getSubWork(subWorkId);
    }
}
