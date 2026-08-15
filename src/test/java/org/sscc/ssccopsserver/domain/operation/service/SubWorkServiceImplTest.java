package org.sscc.ssccopsserver.domain.operation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
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
import org.sscc.ssccopsserver.domain.operation.code.error.OperationErrorCode;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkChecklistItemResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkChecklistItemUpdateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkChecklistItemUpdateResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkCreateResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkDetailResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkTransitionRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkTransitionResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkUpdateRequest;
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
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkTypeEntity;
import org.sscc.ssccopsserver.domain.operation.entity.TransitionAction;
import org.sscc.ssccopsserver.domain.operation.entity.WorkEntity;
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
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;
import org.sscc.ssccopsserver.global.config.ClockConfig;
import org.sscc.ssccopsserver.global.config.JpaAuditingConfig;
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.MemberRoleFixture;
import org.sscc.ssccopsserver.support.SubWorkTypeFixture;

/*
 * @DataJpaTest는 @Configuration을 걸러내므로 JpaAuditingConfig를 명시적으로 들여온다.
 * 없으면 @CreatedDate가 동작하지 않아 crt_dt NOT NULL 위반으로 저장이 실패한다 —
 * 전체 테스트를 함께 돌릴 때는 다른 @SpringBootTest가 Spring Data의 static 감사 핸들러를
 * 먼저 세팅해 우연히 통과하므로, 이 클래스만 단독 실행할 때 드러난다.
 */
@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
// AuthorityPolicy는 @Service라 @DataJpaTest 슬라이스에 없다. MemberServiceImpl이 프로필의
// capabilities를 계산하는 데 쓰므로(#9) 정책과 그 Clock만 슬라이스에 들여온다.
@Import({JpaAuditingConfig.class, AuthorityPolicy.class, ClockConfig.class})
@ActiveProfiles("test")
class SubWorkServiceImplTest {

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
    private MemberEntity registrant;
    private Long ownerId;
    private Long parentWorkId;

    /*
     * 시드가 sub_work_type_id를 지정하지 않으므로(IDENTITY 시퀀스 충돌 방지) 유형은
     * 이름으로 찾는다. 1=예산지출처럼 식별자를 박아 두면 시드 순서에 묶인다.
     */
    private Long approvalNeededTypeId;
    private Long approvalFreeTypeId;

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

        // 등록자와 담당자를 다른 회원으로 둬 둘이 뒤바뀌면 테스트가 깨지게 한다
        registrant = saveMember("20200001", "김도현", "registrant@sscc.org");
        /*
         * 승인·반려는 유형이 지정한 승인자만 할 수 있다 (#47). 승인이 필요한 시드 유형
         * '예산지출'의 승인자가 총무라, 전이를 수행하는 registrant에게 그 역할을 붙여 둔다.
         * 붙이지 않으면 이 클래스의 승인·반려 테스트가 전부 403으로 떨어진다.
         */
        MemberRoleFixture.assign(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                registrant,
                MemberRoleFixture.TREASURER);
        MemberEntity owner = saveMember("20200002", "이서연", "owner@sscc.org");
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

    /*
     * 사용하지 않는 유형은 새로 고를 수 없다 (#43). 없는 유형(404)과 나누는 것은 유형이
     * 실재하기 때문이다 — 목록을 받은 뒤 유형이 꺼진 경우이고, 그때 '없는 유형'이라고
     * 답하면 오해를 부른다.
     */
    @Test
    void createSubWorkRejectsInactiveSubWorkType() {
        SubWorkTypeEntity subWorkType =
                SubWorkTypeFixture.entityOf(
                        subWorkTypeRepository, SubWorkTypeFixture.APPROVAL_FREE);
        subWorkType.changeActivation(false);
        entityManager.flush();
        entityManager.clear();

        assertThatThrownBy(
                        () -> subWorkService.createSubWork(request(approvalFreeTypeId), registrant))
                .isInstanceOf(GeneralException.class)
                .hasFieldOrPropertyWithValue(
                        "errorCode", OperationErrorCode.SUB_WORK_TYPE_INACTIVE);
    }

    /*
     * 화면 하단 안내 문구 — "유형별 승인 규칙은 하위 업무 등록 시 자동 적용되며, 기존 하위
     * 업무에는 소급되지 않습니다." 하위 업무가 등록 시점에 값을 복사해 가므로 성립하는 규칙이라,
     * 유형 관리(#43)가 이 성질을 깨지 않는지 고정한다.
     */
    @Test
    void subWorkTypePolicyChangeDoesNotApplyRetroactively() {
        Long subWorkId = createSubWork(approvalFreeTypeId);
        int checklistCountAtCreation =
                subWorkService.getSubWork(subWorkId, registrant).checklist().size();

        SubWorkTypeEntity subWorkType =
                SubWorkTypeFixture.entityOf(
                        subWorkTypeRepository, SubWorkTypeFixture.APPROVAL_FREE);
        subWorkType.update(
                subWorkType.getTypeName(), true, "PRESIDENT", false, null, List.of("새 점검 항목"));
        entityManager.flush();
        entityManager.clear();

        SubWorkDetailResponse detail = subWorkService.getSubWork(subWorkId, registrant);
        assertThat(detail.approvalStatus()).isEqualTo(ApprovalStatus.NOT_REQUIRED);
        assertThat(detail.checklist()).hasSize(checklistCountAtCreation);
        assertThat(detail.checklist())
                .extracting(SubWorkChecklistItemResponse::article)
                .doesNotContain("새 점검 항목");
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
                subWorkService.createSubWork(request(approvalFreeTypeId), registrant);

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
                subWorkService.createSubWork(request(approvalFreeTypeId), registrant);

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
                                .createSubWork(request(approvalNeededTypeId), registrant)
                                .approvalStatus())
                .isEqualTo(ApprovalStatus.PENDING);
        assertThat(
                        subWorkService
                                .createSubWork(request(approvalFreeTypeId), registrant)
                                .approvalStatus())
                .isEqualTo(ApprovalStatus.NOT_REQUIRED);
    }

    // 상위 업무 진행률은 하위 업무 완료율에서 나온다. 기획 상태 하위 업무만 있으면 0이다
    @Test
    void createSubWorkRecalculatesParentProgressRate() {
        subWorkService.createSubWork(request(approvalFreeTypeId), registrant);
        subWorkService.createSubWork(request(approvalFreeTypeId), registrant);

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
                        approvalFreeTypeId,
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
                        approvalFreeTypeId,
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
                        approvalFreeTypeId,
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
                subWorkService.createSubWork(request(approvalFreeTypeId), registrant).subWorkId();
        entityManager.flush();
        entityManager.clear();

        SubWorkDetailResponse detail = subWorkService.getSubWork(subWorkId, registrant);

        assertThat(detail.subWorkId()).isEqualTo(subWorkId);
        assertThat(detail.workId()).isEqualTo(parentWorkId);
        /*
         * 상위 업무는 식별자만으로 화면의 '상위 업무' 행을 그릴 수 없다 (#70). 제목은 work가
         * 아니라 그 상위 oper가 갖고 있으므로, 연관 하나를 빠뜨리면 여기서 드러난다.
         */
        assertThat(detail.workTitle()).isEqualTo("2026 동아리 박람회");
        assertThat(detail.operationType()).isEqualTo(OperationType.SUB_WORK);
        assertThat(detail.title()).isEqualTo("부스 배치도 확정");
        assertThat(detail.subWorkTypeName()).isEqualTo("내부행사");
        assertThat(detail.workStatus()).isEqualTo(WorkStatus.PLANNING);
        assertThat(detail.priority()).isEqualTo(OperationPriority.HIGH);
        assertThat(detail.content()).isEqualTo("박람회 부스 위치와 동선을 확정한다");
        // 완료 기준 서술은 입력란이 없어 늘 비어 있다. 필드 자체는 유지한다 (AP-15)
        assertThat(detail.completionCriteria()).isNull();
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
                subWorkService.createSubWork(request(approvalFreeTypeId), registrant).subWorkId();
        // 첫 항목만 체크한다 (OPS-013)
        updateChecklistItem(subWorkId, checklistItemIds(subWorkId).get(0), true);

        SubWorkDetailResponse detail = detailOf(subWorkId);

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
                subWorkService.createSubWork(request(approvalNeededTypeId), registrant).subWorkId();
        Long approvalFreeId =
                subWorkService.createSubWork(request(approvalFreeTypeId), registrant).subWorkId();

        SubWorkDetailResponse approvalNeeded =
                subWorkService.getSubWork(approvalNeededId, registrant);
        assertThat(approvalNeeded.approvalRequired()).isTrue();
        assertThat(approvalNeeded.approvalStatus()).isEqualTo(ApprovalStatus.PENDING);

        SubWorkDetailResponse approvalFree = subWorkService.getSubWork(approvalFreeId, registrant);
        assertThat(approvalFree.approvalRequired()).isFalse();
        assertThat(approvalFree.approvalStatus()).isEqualTo(ApprovalStatus.NOT_REQUIRED);
    }

    @Test
    void getSubWorkWithUnknownIdIsRejected() {
        assertThatThrownBy(() -> subWorkService.getSubWork(999L, registrant))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.SUB_WORK_NOT_FOUND);
    }

    // 소프트 삭제된 건은 존재하지 않는 것처럼 404다. 409로 나누면 존재 사실이 새어나간다
    @Test
    void getSoftDeletedSubWorkIsRejected() {
        SubWorkCreateResponse created =
                subWorkService.createSubWork(request(approvalFreeTypeId), registrant);
        operationRepository
                .findById(created.operationId())
                .orElseThrow()
                .softDelete(NOW.toInstant());
        entityManager.flush();
        entityManager.clear();

        assertThatThrownBy(() -> subWorkService.getSubWork(created.subWorkId(), registrant))
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
     * 맡기면 응답을 조립하는 동안 쿼리가 하나씩 더 나간다 (DB-13). 연관이 늘어도 EntityGraph에
     * 넣으면 이 수가 유지되는지 못 박아 둔다.
     *
     * 승인이 필요 없는 유형은 3회다: 하위 업무 1 + 체크리스트 1 + 최근 반려 1 (#58).
     * 승인자 판정이 유형만 보고 끝나 회원의 역할을 조회하지 않고, 투표가 없어 정족수도 세지 않는다.
     */
    @Test
    void getSubWorkRunsThreeQueriesForApprovalFreeType() {
        Long subWorkId =
                subWorkService.createSubWork(request(approvalFreeTypeId), registrant).subWorkId();

        assertThat(queryCountOfDetail(subWorkId)).isEqualTo(3);
    }

    /*
     * 승인이 필요한 유형은 승인자 판정에 회원의 현재 역할이 필요해 한 번 더 조회한다 (4회).
     * 정족수 유형이 아니면 회차·찬성 수·내 표는 세지 않는다 — 투표 자체가 없는 유형이다.
     */
    @Test
    void getSubWorkRunsOneMoreQueryForApprovalNeededType() {
        Long subWorkId =
                subWorkService.createSubWork(request(approvalNeededTypeId), registrant).subWorkId();

        assertThat(queryCountOfDetail(subWorkId)).isEqualTo(4);
    }

    private long queryCountOfDetail(Long subWorkId) {
        entityManager.flush();
        entityManager.clear();

        Statistics statistics =
                entityManager
                        .getEntityManager()
                        .getEntityManagerFactory()
                        .unwrap(SessionFactory.class)
                        .getStatistics();
        statistics.clear();

        subWorkService.getSubWork(subWorkId, registrant);

        return statistics.getPrepareStatementCount();
    }

    // TR-01 착수. 승인 상태는 검토요청 전까지 등록 시점 값 그대로다
    @Test
    void startMovesPlanningToInProgress() {
        Long subWorkId = createSubWork(approvalNeededTypeId);

        SubWorkTransitionResponse response = transition(subWorkId, TransitionAction.START, null);

        assertThat(response.previousWorkStatus()).isEqualTo(WorkStatus.PLANNING);
        assertThat(response.workStatus()).isEqualTo(WorkStatus.IN_PROGRESS);
        assertThat(response.approvalStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(response.completedAt()).isNull();
    }

    // TR-02 검토요청. 승인이 필요한 유형은 여기서 승인 대기가 된다
    @Test
    void requestReviewPutsApprovalNeededTypeIntoPending() {
        Long subWorkId = subWorkInReview(approvalNeededTypeId);

        SubWorkDetailResponse detail = detailOf(subWorkId);

        assertThat(detail.workStatus()).isEqualTo(WorkStatus.REVIEW);
        assertThat(detail.approvalStatus()).isEqualTo(ApprovalStatus.PENDING);
    }

    // 승인이 필요 없는 유형(REQ-016 저위험 면제)은 검토 단계에서도 승인 상태가 불필요 그대로다
    @Test
    void requestReviewKeepsApprovalFreeTypeNotRequired() {
        Long subWorkId = subWorkInReview(approvalFreeTypeId);

        assertThat(detailOf(subWorkId).approvalStatus()).isEqualTo(ApprovalStatus.NOT_REQUIRED);
    }

    /*
     * TR-03 승인·완료. 승인과 완료가 한 단계라 승인 상태와 업무 상태가 함께 바뀌고,
     * 완료 일시와 상위 업무 진행률도 이 전이에서 갱신된다.
     */
    @Test
    void approveCompleteMarksDoneAndRecordsCompletedAt() {
        Long subWorkId = subWorkInReview(approvalNeededTypeId);
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
        Long subWorkId = subWorkInReview(approvalFreeTypeId);
        completeChecklist(subWorkId);

        SubWorkTransitionResponse response =
                transition(subWorkId, TransitionAction.APPROVE_COMPLETE, null);

        assertThat(response.workStatus()).isEqualTo(WorkStatus.DONE);
        assertThat(response.approvalStatus()).isEqualTo(ApprovalStatus.NOT_REQUIRED);
    }

    // 완료 체크리스트를 다 채우지 않은 건은 완료되지 않는다 (REQ-021)
    @Test
    void approveCompleteWithUnfinishedChecklistIsRejected() {
        Long subWorkId = subWorkInReview(approvalNeededTypeId);

        assertThatThrownBy(() -> transition(subWorkId, TransitionAction.APPROVE_COMPLETE, null))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.COMPLETION_CRITERIA_UNMET);
        assertThat(detailOf(subWorkId).workStatus()).isEqualTo(WorkStatus.REVIEW);
    }

    // TR-04 반려. 상태가 진행으로 회귀하고 승인 상태는 반려로 남는다
    @Test
    void rejectReturnsToInProgress() {
        Long subWorkId = subWorkInReview(approvalNeededTypeId);

        SubWorkTransitionResponse response =
                transition(subWorkId, TransitionAction.REJECT, "예산안 대비 초과");

        assertThat(response.workStatus()).isEqualTo(WorkStatus.IN_PROGRESS);
        assertThat(response.approvalStatus()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(response.completedAt()).isNull();
    }

    // 반려는 사유 없이 성립하지 않는다 (VR-O06). 400이 아니라 422다
    @Test
    void rejectWithoutReasonIsRejected() {
        Long subWorkId = subWorkInReview(approvalNeededTypeId);

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
        Long subWorkId = subWorkInReview(approvalNeededTypeId);
        transition(subWorkId, TransitionAction.REJECT, "예산안 대비 초과");

        SubWorkTransitionResponse response =
                transition(subWorkId, TransitionAction.REQUEST_REVIEW, null);

        assertThat(response.previousApprovalStatus()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(response.approvalStatus()).isEqualTo(ApprovalStatus.REAPPROVAL_REQUIRED);
    }

    // 전이표에 없는 조합은 전부 차단한다 (BR-O03·VR-O04). 기획 상태에서 완료로 건너뛸 수 없다
    @Test
    void transitionOutsideTransitionTableIsRejected() {
        Long subWorkId = createSubWork(approvalNeededTypeId);

        assertThatThrownBy(() -> transition(subWorkId, TransitionAction.APPROVE_COMPLETE, null))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.TRANSITION_NOT_ALLOWED);
    }

    // TR-X1 — 완료된 건을 진행으로 되돌리는 경로는 없다
    @Test
    void completedSubWorkCannotBeReopened() {
        Long subWorkId = subWorkInReview(approvalFreeTypeId);
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
        Long subWorkId = createSubWork(approvalNeededTypeId);
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
        Long subWorkId = subWorkInReview(approvalNeededTypeId);
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
        Long subWorkId = subWorkInReview(approvalNeededTypeId);
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
        Long subWorkId = subWorkInReview(approvalNeededTypeId);
        completeChecklist(subWorkId);
        MemberEntity approver = memberRepository.findById(ownerId).orElseThrow();
        // 등록자가 아닌 승인자여야 하므로 담당자에게도 승인자 역할을 붙인다 (#47)
        MemberRoleFixture.assign(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                approver,
                MemberRoleFixture.TREASURER);

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
                subWorkService.createSubWork(request(approvalFreeTypeId), registrant);
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

    // 화면의 체크박스 하나. 응답의 요약이 곧 '1/4 완료' 표기다 (OPS-013)
    @Test
    void checkChecklistItemUpdatesItemAndSummary() {
        Long subWorkId = createSubWork(approvalFreeTypeId);
        Long firstItemId = checklistItemIds(subWorkId).get(0);

        SubWorkChecklistItemUpdateResponse response =
                updateChecklistItem(subWorkId, firstItemId, true);

        assertThat(response.subWorkId()).isEqualTo(subWorkId);
        assertThat(response.item().checklistItemId()).isEqualTo(firstItemId);
        assertThat(response.item().isCompleted()).isTrue();
        assertThat(response.item().sortOrder()).isEqualTo(1);
        assertThat(response.checklistSummary().completedCount()).isEqualTo(1);
        assertThat(response.checklistSummary().totalCount()).isEqualTo(4);

        // 응답의 요약과 다음 상세 조회의 요약이 같은 값이어야 화면이 흔들리지 않는다
        assertThat(detailOf(subWorkId).checklistSummary().completedCount()).isEqualTo(1);
    }

    // 체크 해제도 같은 경로다. 완료 조건이 되돌아가는 것을 막지 않는다
    @Test
    void uncheckChecklistItemDecreasesSummary() {
        Long subWorkId = createSubWork(approvalFreeTypeId);
        Long firstItemId = checklistItemIds(subWorkId).get(0);
        updateChecklistItem(subWorkId, firstItemId, true);

        SubWorkChecklistItemUpdateResponse response =
                updateChecklistItem(subWorkId, firstItemId, false);

        assertThat(response.item().isCompleted()).isFalse();
        assertThat(response.checklistSummary().completedCount()).isZero();
    }

    // 더블 탭이 완료 수를 두 번 올리지 않는다 — 멱등이라 별도 멱등성 키를 두지 않는다 (AP-16)
    @Test
    void checkingSameItemTwiceCountsOnce() {
        Long subWorkId = createSubWork(approvalFreeTypeId);
        Long firstItemId = checklistItemIds(subWorkId).get(0);

        updateChecklistItem(subWorkId, firstItemId, true);
        SubWorkChecklistItemUpdateResponse response =
                updateChecklistItem(subWorkId, firstItemId, true);

        assertThat(response.checklistSummary().completedCount()).isEqualTo(1);
    }

    /*
     * 체크는 상태 전이가 아니다. 스테퍼(업무 상태)·승인 칩(승인 상태)이 그대로여야 하고,
     * 상위 업무 진행률도 하위 업무 완료 건수에서 나오므로 움직이지 않는다.
     */
    @Test
    void updateChecklistItemDoesNotChangeStatusesOrParentProgress() {
        Long subWorkId = createSubWork(approvalNeededTypeId);

        completeChecklist(subWorkId);

        SubWorkDetailResponse detail = detailOf(subWorkId);
        assertThat(detail.workStatus()).isEqualTo(WorkStatus.PLANNING);
        assertThat(detail.approvalStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(detail.completedAt()).isNull();
        assertThat(workRepository.findById(parentWorkId).orElseThrow().getProgressRate())
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(subWorkStatusHistoryRepository.count()).isZero();
    }

    /*
     * 이 이슈의 존재 이유. 체크 API가 없던 동안에는 완료 점검 항목이 있는 유형이 완료 승인에서
     * 항상 COMPLETION_CRITERIA_UNMET(409)로 막혔다 — 마지막 항목까지 체크하면 통과한다.
     */
    @Test
    void approveCompleteSucceedsAfterCheckingEveryItem() {
        Long subWorkId = subWorkInReview(approvalNeededTypeId);
        List<Long> itemIds = checklistItemIds(subWorkId);

        for (Long itemId : itemIds.subList(0, itemIds.size() - 1)) {
            updateChecklistItem(subWorkId, itemId, true);
        }
        // 마지막 항목을 남긴 동안에는 여전히 막힌다
        assertThatThrownBy(() -> transition(subWorkId, TransitionAction.APPROVE_COMPLETE, null))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.COMPLETION_CRITERIA_UNMET);

        SubWorkChecklistItemUpdateResponse lastCheck =
                updateChecklistItem(subWorkId, itemIds.get(itemIds.size() - 1), true);
        assertThat(lastCheck.checklistSummary().completedCount())
                .isEqualTo(lastCheck.checklistSummary().totalCount());

        SubWorkTransitionResponse response =
                transition(subWorkId, TransitionAction.APPROVE_COMPLETE, null);

        assertThat(response.workStatus()).isEqualTo(WorkStatus.DONE);
        assertThat(response.completedAt()).isEqualTo(NOW);
    }

    /*
     * 완료된 건의 체크는 되돌릴 수 없다 — '완료됐는데 완료 조건 미충족'인 데이터를 만들지 않는다.
     * 전용 코드를 새로 만들지 않고 TRANSITION_NOT_ALLOWED(409)를 재사용한다.
     */
    @Test
    void updateChecklistItemOnCompletedSubWorkIsRejected() {
        Long subWorkId = subWorkInReview(approvalFreeTypeId);
        completeChecklist(subWorkId);
        transition(subWorkId, TransitionAction.APPROVE_COMPLETE, null);
        Long firstItemId = checklistItemIds(subWorkId).get(0);

        assertThatThrownBy(() -> updateChecklistItem(subWorkId, firstItemId, false))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.TRANSITION_NOT_ALLOWED);
        assertThat(detailOf(subWorkId).checklistSummary().completedCount()).isEqualTo(4);
    }

    // 반려로 진행에 되돌아온 담당자가 남은 항목을 마저 채우는 것이 화면의 흐름이다
    @Test
    void checklistIsEditableAfterRejection() {
        Long subWorkId = subWorkInReview(approvalNeededTypeId);
        transition(subWorkId, TransitionAction.REJECT, "현장 답사 결과 누락");
        Long firstItemId = checklistItemIds(subWorkId).get(0);

        assertThat(updateChecklistItem(subWorkId, firstItemId, true).item().isCompleted()).isTrue();
    }

    /*
     * 경로의 하위 업무에 속하지 않는 항목은 체크할 수 없다 (IDOR). 존재 사실을 알려주지 않기
     * 위해 403이 아니라 404이며, 남의 항목은 그대로 미완료로 남는다.
     */
    @Test
    void updateChecklistItemOfAnotherSubWorkIsRejected() {
        Long subWorkId = createSubWork(approvalFreeTypeId);
        Long otherSubWorkId = createSubWork(approvalFreeTypeId);
        Long otherItemId = checklistItemIds(otherSubWorkId).get(0);

        assertThatThrownBy(() -> updateChecklistItem(subWorkId, otherItemId, true))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.CHECKLIST_ITEM_NOT_FOUND);
        assertThat(detailOf(otherSubWorkId).checklistSummary().completedCount()).isZero();
    }

    @Test
    void updateUnknownChecklistItemIsRejected() {
        Long subWorkId = createSubWork(approvalFreeTypeId);

        assertThatThrownBy(() -> updateChecklistItem(subWorkId, 999L, true))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.CHECKLIST_ITEM_NOT_FOUND);
    }

    @Test
    void updateChecklistItemOnUnknownSubWorkIsRejected() {
        assertThatThrownBy(() -> updateChecklistItem(999L, 1L, true))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.SUB_WORK_NOT_FOUND);
    }

    // 소프트 삭제된 건은 조회·전이와 마찬가지로 존재하지 않는 것처럼 다룬다
    @Test
    void updateChecklistItemOnSoftDeletedSubWorkIsRejected() {
        SubWorkCreateResponse created =
                subWorkService.createSubWork(request(approvalFreeTypeId), registrant);
        Long itemId = checklistItemIds(created.subWorkId()).get(0);
        operationRepository
                .findById(created.operationId())
                .orElseThrow()
                .softDelete(NOW.toInstant());

        assertThatThrownBy(() -> updateChecklistItem(created.subWorkId(), itemId, true))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.SUB_WORK_NOT_FOUND);
    }

    // ---------------------------------------------------------------- OPS-030 수정

    @Test
    void updateSubWorkChangesAllEditableFields() {
        Long subWorkId = createSubWork(approvalFreeTypeId);
        MemberEntity newOwner = saveMember("20200003", "박준호", "new-owner@sscc.org");
        OffsetDateTime newStart = START.plusDays(1);
        OffsetDateTime newEnd = END.plusDays(1);
        OffsetDateTime newDueAt = newEnd;

        SubWorkDetailResponse updated =
                subWorkService.updateSubWork(
                        subWorkId,
                        new SubWorkUpdateRequest(
                                "수정 후 제목",
                                newOwner.getId(),
                                newStart,
                                newEnd,
                                newDueAt,
                                OperationPriority.LOW,
                                "수정 후 업무 내용",
                                "수정 후 완료 기준",
                                "https://docs.example.com/updated"),
                        registrant);

        assertThat(updated.subWorkId()).isEqualTo(subWorkId);
        assertThat(updated.title()).isEqualTo("수정 후 제목");
        assertThat(updated.owner().memberId()).isEqualTo(newOwner.getId());
        assertThat(updated.startAt().toInstant()).isEqualTo(newStart.toInstant());
        assertThat(updated.endAt().toInstant()).isEqualTo(newEnd.toInstant());
        assertThat(updated.dueAt().toInstant()).isEqualTo(newDueAt.toInstant());
        assertThat(updated.priority()).isEqualTo(OperationPriority.LOW);
        assertThat(updated.content()).isEqualTo("수정 후 업무 내용");
        // 등록 화면에 입력란이 없어 늘 NULL이던 값을 처음으로 채울 수 있는 경로다 (#70)
        assertThat(updated.completionCriteria()).isEqualTo("수정 후 완료 기준");
        assertThat(updated.externalLink()).isEqualTo("https://docs.example.com/updated");
        // 상태·유형·상위 업무는 이 경로로 바꿀 방법이 없다(요청 DTO에 필드가 아예 없다)
        assertThat(updated.workStatus()).isEqualTo(WorkStatus.PLANNING);
        assertThat(updated.subWorkTypeId()).isEqualTo(approvalFreeTypeId);
        assertThat(updated.workId()).isEqualTo(parentWorkId);

        // sub_work_ttl은 oper_ttl과 값이 같아야 한다 — 서비스가 둘을 나란히 바꿔야 성립한다
        SubWorkEntity subWork = subWorkRepository.findById(subWorkId).orElseThrow();
        assertThat(subWork.getOperation().getTitle()).isEqualTo("수정 후 제목");

        assertThat(detailOf(subWorkId).title()).isEqualTo("수정 후 제목");
    }

    // 화면 기본값이 '보통'이라 우선순위가 빠진 요청도 NORMAL로 저장돼야 한다 (등록과 같은 규칙)
    @Test
    void updateSubWorkWithoutPriorityDefaultsToNormal() {
        Long subWorkId = createSubWork(approvalFreeTypeId);

        SubWorkDetailResponse updated =
                subWorkService.updateSubWork(
                        subWorkId,
                        new SubWorkUpdateRequest(
                                "우선순위 미지정 수정", ownerId, START, END, null, null, null, null, null),
                        registrant);

        assertThat(updated.priority()).isEqualTo(OperationPriority.NORMAL);
        // content·completionCriteria·externalLink도 생략하면 지워진다 — 전체 교체다
        assertThat(updated.content()).isNull();
        assertThat(updated.completionCriteria()).isNull();
        assertThat(updated.externalLink()).isNull();
        assertThat(updated.dueAt()).isNull();
    }

    @Test
    void updateSubWorkWithUnknownOwnerIsRejected() {
        Long subWorkId = createSubWork(approvalFreeTypeId);

        SubWorkUpdateRequest request =
                new SubWorkUpdateRequest(
                        "담당자 교체 실패", ownerId + 999, START, END, null, null, null, null, null);

        assertThatThrownBy(() -> subWorkService.updateSubWork(subWorkId, request, registrant))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.OWNER_NOT_ACTIVE_MEMBER);

        // 실패한 수정은 아무것도 바꾸지 않는다
        assertThat(detailOf(subWorkId).title()).isNotEqualTo("담당자 교체 실패");
    }

    @Test
    void updateSubWorkWithInvertedPeriodIsRejected() {
        Long subWorkId = createSubWork(approvalFreeTypeId);

        SubWorkUpdateRequest request =
                new SubWorkUpdateRequest(
                        "기간 역전 수정", ownerId, END, START, null, null, null, null, null);

        assertThatThrownBy(() -> subWorkService.updateSubWork(subWorkId, request, registrant))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.INVALID_OPERATION_PERIOD);
    }

    @Test
    void updateSubWorkWithUnknownIdIsRejected() {
        SubWorkUpdateRequest request =
                new SubWorkUpdateRequest(
                        "존재 확인용 수정", ownerId, START, END, null, null, null, null, null);

        assertThatThrownBy(() -> subWorkService.updateSubWork(999L, request, registrant))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.SUB_WORK_NOT_FOUND);
    }

    // 소프트 삭제된 건은 조회·전이·체크와 마찬가지로 존재하지 않는 것처럼 다룬다
    @Test
    void updateSubWorkOnSoftDeletedSubWorkIsRejected() {
        SubWorkCreateResponse created =
                subWorkService.createSubWork(request(approvalFreeTypeId), registrant);
        operationRepository
                .findById(created.operationId())
                .orElseThrow()
                .softDelete(NOW.toInstant());
        entityManager.flush();
        entityManager.clear();

        SubWorkUpdateRequest request =
                new SubWorkUpdateRequest(
                        "삭제된 건 수정", ownerId, START, END, null, null, null, null, null);

        assertThatThrownBy(
                        () ->
                                subWorkService.updateSubWork(
                                        created.subWorkId(), request, registrant))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.SUB_WORK_NOT_FOUND);
    }

    /*
     * updatedAt(mdfcn_dt)은 @LastModifiedDate라 flush 시점에야 채워진다. 응답을 만들기 전에
     * flush하지 않으면 방금 바꾼 값인데도 직전 수정 시각이 그대로 나간다 — 응답이 실제
     * 저장값과 같은지로 그 회귀를 잡는다.
     */
    @Test
    void updateSubWorkResponseReflectsFlushedUpdatedAt() {
        Long subWorkId = createSubWork(approvalFreeTypeId);

        SubWorkDetailResponse updated =
                subWorkService.updateSubWork(
                        subWorkId,
                        new SubWorkUpdateRequest(
                                "갱신 시각 확인", ownerId, START, END, null, null, null, null, null),
                        registrant);

        Instant persistedUpdatedAt =
                operationRepository
                        .findById(
                                subWorkRepository
                                        .findById(subWorkId)
                                        .orElseThrow()
                                        .getOperation()
                                        .getId())
                        .orElseThrow()
                        .getUpdatedAt();
        assertThat(updated.updatedAt().toInstant()).isEqualTo(persistedUpdatedAt);
    }

    // 승인·정족수 등 '보는 사람에 따라 갈리는 값'은 수정 후에도 조회(getSubWork)와 같은 규칙을 쓴다
    @Test
    void updateSubWorkResponseCarriesSameApprovalDerivedValuesAsGet() {
        Long subWorkId = createSubWork(approvalNeededTypeId);

        SubWorkDetailResponse updated =
                subWorkService.updateSubWork(
                        subWorkId,
                        new SubWorkUpdateRequest(
                                "승인 값 확인", ownerId, START, END, null, null, null, null, null),
                        registrant);

        SubWorkDetailResponse fetched = detailOf(subWorkId);
        assertThat(updated.canApprove()).isEqualTo(fetched.canApprove());
        assertThat(updated.canReject()).isEqualTo(fetched.canReject());
        assertThat(updated.quorum()).isEqualTo(fetched.quorum());
    }

    /*
     * 회원 상태 변경(#78)의 경고에 실리는 '담당 중인 하위 업무' 건수.
     *
     * 완료된 건을 세면 오래 활동한 회원일수록 경고가 영영 남아, 실제로 인수인계가 필요한
     * 상황과 구별되지 않는다. 담당자가 아닌 등록자에게 잡히지 않는 것도 함께 확인한다 —
     * 두 자리를 헷갈리면 "업무를 만든 사람"이 탈퇴할 때마다 경고가 뜬다.
     */
    @Test
    void countOngoingByOwnerCountsOnlyUnfinishedSubWorksOfThatOwner() {
        createSubWork(approvalFreeTypeId);

        Long doneSubWorkId = subWorkInReview(approvalFreeTypeId);
        completeChecklist(doneSubWorkId);
        transition(doneSubWorkId, TransitionAction.APPROVE_COMPLETE, null);

        entityManager.flush();
        entityManager.clear();

        assertThat(subWorkService.countOngoingByOwner(ownerId)).isEqualTo(1);
        assertThat(subWorkService.countOngoingByOwner(registrant.getId())).isZero();
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

    // 체크리스트를 전부 체크한 상태로 만든다. 화면에서 체크박스를 하나씩 누르는 것과 같은 경로다
    private void completeChecklist(Long subWorkId) {
        for (Long itemId : checklistItemIds(subWorkId)) {
            updateChecklistItem(subWorkId, itemId, true);
        }
    }

    private List<Long> checklistItemIds(Long subWorkId) {
        entityManager.flush();
        entityManager.clear();
        SubWorkEntity subWork = subWorkRepository.findById(subWorkId).orElseThrow();
        return subWorkChecklistItemRepository.findBySubWorkOrderBySortOrderAsc(subWork).stream()
                .map(SubWorkChecklistItemEntity::getId)
                .toList();
    }

    private SubWorkChecklistItemUpdateResponse updateChecklistItem(
            Long subWorkId, Long checklistItemId, boolean completed) {
        entityManager.flush();
        entityManager.clear();
        return subWorkService.updateChecklistItem(
                subWorkId,
                checklistItemId,
                new SubWorkChecklistItemUpdateRequest(completed),
                registrant);
    }

    private Long subWorkWithDueAt(OffsetDateTime dueAt) {
        return subWorkService
                .createSubWork(
                        new SubWorkCreateRequest(
                                parentWorkId,
                                "마감 판정용 하위 업무",
                                approvalFreeTypeId,
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
        return subWorkService.getSubWork(subWorkId, registrant);
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
}
