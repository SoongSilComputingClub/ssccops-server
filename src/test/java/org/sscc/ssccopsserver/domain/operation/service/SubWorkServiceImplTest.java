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
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkChecklistHistoryResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkChecklistItemResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkChecklistItemSaveRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkChecklistItemUpdateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkChecklistItemUpdateResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkChecklistMutationResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkCreateResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkDetailResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkTransitionRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkTransitionResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkUpdateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.WorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.entity.ApprovalStatus;
import org.sscc.ssccopsserver.domain.operation.entity.ChecklistChangeType;
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
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkChecklistHistoryRepository;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkChecklistItemRepository;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkRejectionRepository;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkRepository;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkStatusHistoryRepository;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkTypeRepository;
import org.sscc.ssccopsserver.domain.operation.repository.WorkRepository;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;
import org.sscc.ssccopsserver.global.audit.AuditLog;
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

    /*
     * 지연 판정은 시각이 아니라 일자로 한다 (#121). 그 경계를 재는 값들이라 NOW에서 파생한다 —
     * 날짜를 따로 적으면 NOW를 옮겼을 때 한쪽만 따라오지 않는다.
     */
    private static final OffsetDateTime TODAY_START =
            NOW.toLocalDate().atStartOfDay().atOffset(KST);
    private static final OffsetDateTime TODAY_MORNING = NOW.minusHours(3);
    private static final OffsetDateTime YESTERDAY_END = TODAY_START.minusMinutes(1);

    @Autowired private OperationRepository operationRepository;
    @Autowired private WorkRepository workRepository;
    @Autowired private SubWorkRepository subWorkRepository;
    @Autowired private SubWorkTypeRepository subWorkTypeRepository;
    @Autowired private SubWorkChecklistItemRepository subWorkChecklistItemRepository;
    @Autowired private SubWorkChecklistHistoryRepository subWorkChecklistHistoryRepository;
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

    /*
     * countOngoingByOwner 는 SubWorkService 가 아니라 회원 도메인이 선언한 포트
     * (MemberSubWorkLoadProvider)의 메서드이고 운영 쪽 구현이 이 클래스다(ssccops#242).
     * 같은 리포지토리 질의를 쓰므로 여기서 함께 검증한다.
     */
    private SubWorkOwnerLoadProvider subWorkOwnerLoadProvider;
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
                        new MemberInitialHistoryRecorder(
                                memberGradeHistoryRepository, memberStatusHistoryRepository),
                        new MemberProfileChangeRecorder(memberChangeHistoryRepository),
                        authorityPolicy,
                        new MemberLinkAttemptLimiter(FIXED_CLOCK),
                        FIXED_CLOCK,
                        new AuditLog());
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
                        subWorkChecklistHistoryRepository,
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
                        new AuditLog(),
                        entityManager.getEntityManager());
        subWorkOwnerLoadProvider = new SubWorkOwnerLoadProvider(subWorkRepository);

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

        SubWorkCreateRequest request = request(approvalFreeTypeId);

        assertThatThrownBy(() -> subWorkService.createSubWork(request, registrant))
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
                subWorkType.getTypeName(),
                true,
                "SUB_WORK_APPROVE_PRESIDENT",
                false,
                null,
                List.of("새 점검 항목"));
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

    /*
     * 등록 응답의 isDelayed도 조회 시점 판정값이다 (#121). 이 자리만 dly_yn 컬럼을 읽고 있어서,
     * 마감이 이미 지난 건으로 등록하면 등록 응답은 false인데 곧바로 여는 목록·상세는 true인
     * 상태가 됐다. 그 컬럼은 채우지 않기로 결정한 값이라 언제나 false다 (#117).
     */
    @Test
    void createSubWorkResponseJudgesDelayInsteadOfReadingDeadColumn() {
        SubWorkCreateResponse response = createWithDueAt(NOW.minusDays(1));

        assertThat(response.isDelayed()).isTrue();
        assertThat(subWorkRepository.findById(response.subWorkId()).orElseThrow().isDelayed())
                .isFalse();
    }

    // 그리고 그 판정도 날짜 단위다 — 오늘 아침 마감으로 등록해도 등록 응답은 지연이 아니다
    @Test
    void createSubWorkResponseIsNotDelayedWhenDueToday() {
        assertThat(createWithDueAt(TODAY_MORNING).isDelayed()).isFalse();
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

    // 하위 업무를 등록해도 상위 업무의 저장 진행률은 건드리지 않는다 (AGG-05, #117)
    @Test
    void createSubWorkDoesNotTouchStoredParentProgressRate() {
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

        Long subWorkId = created.subWorkId();

        assertThatThrownBy(() -> subWorkService.getSubWork(subWorkId, registrant))
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

    /*
     * 마감일이 오늘이면 마감 시각이 지났어도 지연이 아니다 (#121). 초 단위로 재던 동안에는
     * 이 건이 대시보드에서 'D-DAY'(화면의 날짜 단위 D-day)와 '지연'(서버 판정)으로 동시에
     * 표시됐다 — 결함이 살아남은 것은 기존 검증이 전부 NOW ± N일이라 같은 날 안의 시각차를
     * 아무도 보지 않았기 때문이다.
     */
    @Test
    void isDelayedIsFalseWhenDueTodayEvenAfterDueTime() {
        assertThat(detailOf(subWorkWithDueAt(TODAY_MORNING)).isDelayed()).isFalse();
    }

    // 오늘 0시 마감도 오늘 하루는 지연이 아니다 — 경계는 마감 '일자'이지 시각이 아니다
    @Test
    void isDelayedIsFalseWhenDueAtTodayMidnight() {
        assertThat(detailOf(subWorkWithDueAt(TODAY_START)).isDelayed()).isFalse();
    }

    // 그리고 자정을 넘기는 순간 지연이 된다 — 어제 23:59 마감은 오늘 지연이다
    @Test
    void isDelayedTurnsTrueAtMidnightAfterDueDate() {
        assertThat(detailOf(subWorkWithDueAt(YESTERDAY_END)).isDelayed()).isTrue();
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
     * 승인이 필요 없는 유형은 6회다: 하위 업무 1 + 체크리스트 1 + 최근 반려 1(#58) +
     * 담당자 판정 3(#101) — canApprove·canReject를 승인자 판정이 아니라 담당자 판정
     * (SubWorkOwnershipPolicy.isOwnerOrManager)으로 계산하기 때문이다. 조회자(registrant)가
     * 이 건의 담당자가 아니라서(위 request()의 ownerId와 다른 회원) 담당자 여부만으로는
     * 끝나지 못하고 WORK_MANAGE 보유 여부까지 조회한다(capabilitiesOf: 유효 역할 1 + 부여된
     * 권한 1 + 트리 펼침 1). 조회자가 담당자 본인이면 그 세 번은 들지 않는다 — isOwner를
     * 먼저 보고 사실이면 hasAuthority를 부르지 않는다(SubWorkOwnershipPolicy 주석).
     */
    @Test
    void getSubWorkRunsSixQueriesForApprovalFreeType() {
        Long subWorkId =
                subWorkService.createSubWork(request(approvalFreeTypeId), registrant).subWorkId();

        assertThat(queryCountOfDetail(subWorkId)).isEqualTo(6);
    }

    /*
     * 승인이 필요한 유형은 7회다: 기본 3(하위 업무·체크리스트·최근 반려) + 승인자 판정의
     * 권한 펼침 3(#123 — capabilitiesOf: 유효 역할 1 + 부여된 권한 1 + 트리 간선 1) +
     * 승인자 결재 권한 표시명 1. 정족수 유형이 아니면 회차·찬성 수·내 표는 세지 않는다 —
     * 투표 자체가 없는 유형이다.
     */
    @Test
    void getSubWorkRunsSevenQueriesForApprovalNeededType() {
        Long subWorkId =
                subWorkService.createSubWork(request(approvalNeededTypeId), registrant).subWorkId();

        assertThat(queryCountOfDetail(subWorkId)).isEqualTo(7);
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
     * TR-03 승인·완료. 승인과 완료가 한 단계라 승인 상태와 업무 상태가 함께 바뀌고
     * 완료 일시가 채워진다.
     *
     * parentWorkProgressRate는 저장 컬럼을 그대로 읽는 필드라 0이다 — 그 컬럼을 채우지 않기로
     * 했기 때문이다 (AGG-05, #117). 진행률의 정본은 상세(OPS-003)·목록(OPS-020)이 계산해
     * 내려주는 AGG-01이며, 웹도 이 필드를 받지 않는다.
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
        assertThat(response.parentWorkProgressRate()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /*
     * 완료 승인은 하위 업무만 바꾸고 상위 업무 행은 건드리지 않는다 (AGG-05, #117).
     * 예전에는 이 전이가 완료 개수를 다시 세어 work_prgrs_rt를 UPDATE 했다 — 그 값이
     * 응답의 진행률(AGG-01 평균)과 어긋나는 원인이었다.
     */
    @Test
    void approveCompleteDoesNotTouchStoredParentProgressRate() {
        Long subWorkId = subWorkInReview(approvalNeededTypeId);
        completeChecklist(subWorkId);

        transition(subWorkId, TransitionAction.APPROVE_COMPLETE, null);

        entityManager.flush();
        entityManager.clear();
        assertThat(workRepository.findById(parentWorkId).orElseThrow().getProgressRate())
                .isEqualByComparingTo(BigDecimal.ZERO);
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

        Long subWorkId = created.subWorkId();

        assertThatThrownBy(() -> transition(subWorkId, TransitionAction.START, null))
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

        Long subWorkId = created.subWorkId();

        assertThatThrownBy(() -> updateChecklistItem(subWorkId, itemId, true))
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

        Long subWorkId = created.subWorkId();

        assertThatThrownBy(() -> subWorkService.updateSubWork(subWorkId, request, registrant))
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

        assertThat(subWorkOwnerLoadProvider.countOngoingByOwner(ownerId)).isEqualTo(1);
        assertThat(subWorkOwnerLoadProvider.countOngoingByOwner(registrant.getId())).isZero();
    }

    // ---------------------------------------------------------------- 삭제 (#125)

    // sub-work 삭제는 자기 operation만 소프트 삭제한다 — 상위 work·다른 sub-work는 건드리지 않는다
    @Test
    void deleteSubWorkSoftDeletesOnlyItsOwnOperation() {
        Long subWorkId = createSubWork(approvalFreeTypeId);

        subWorkService.deleteSubWork(subWorkId);
        entityManager.flush();
        entityManager.clear();

        assertThat(subWorkRepository.findById(subWorkId).orElseThrow().getOperation().isDeleted())
                .isTrue();
        assertThat(workRepository.findById(parentWorkId).orElseThrow().getOperation().isDeleted())
                .isFalse();
    }

    // 상태와 무관하게 항상 삭제할 수 있다 — 완료(DONE)된 sub-work도 예외가 아니다
    @Test
    void deleteSubWorkAllowsCompletedSubWork() {
        Long subWorkId = subWorkInReview(approvalFreeTypeId);
        completeChecklist(subWorkId);
        transition(subWorkId, TransitionAction.APPROVE_COMPLETE, null);
        entityManager.flush();
        entityManager.clear();

        subWorkService.deleteSubWork(subWorkId);
        entityManager.flush();
        entityManager.clear();

        assertThat(subWorkRepository.findById(subWorkId).orElseThrow().getOperation().isDeleted())
                .isTrue();
    }

    @Test
    void deleteSubWorkWithUnknownIdThrowsNotFound() {
        assertThatThrownBy(() -> subWorkService.deleteSubWork(999_999L))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.SUB_WORK_NOT_FOUND);
    }

    @Test
    void deleteAlreadyDeletedSubWorkThrowsAlreadyDeleted() {
        Long subWorkId = createSubWork(approvalFreeTypeId);
        subWorkService.deleteSubWork(subWorkId);

        assertThatThrownBy(() -> subWorkService.deleteSubWork(subWorkId))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.ALREADY_DELETED);
    }

    /*
     * ===== 완료 점검 항목 추가·수정·삭제 (#307) =====
     *
     * 상태 잠금·체크된 항목 삭제 금지·이력이 세트다. 셀을 하나만 빼면 "심사 직전에
     * 기준을 낮추는 경로"가 다시 생기므로 세 가지를 함께 본다.
     */

    // 추가된 항목은 목록 끝에 붙고 미완료로 시작한다 — 완료 조건이 한 칸 늘어난다
    @Test
    void addChecklistItemAppendsAtTheEnd() {
        Long subWorkId = createSubWork(approvalFreeTypeId);

        SubWorkChecklistMutationResponse response = addChecklistItem(subWorkId, "현장 답사");

        assertThat(response.subWorkId()).isEqualTo(subWorkId);
        assertThat(response.item().article()).isEqualTo("현장 답사");
        assertThat(response.item().sortOrder()).isEqualTo(5);
        assertThat(response.item().isCompleted()).isFalse();
        assertThat(response.checklist()).hasSize(5);
        assertThat(response.checklist().get(4).article()).isEqualTo("현장 답사");
        assertThat(response.checklistSummary().totalCount()).isEqualTo(5);
        assertThat(response.isChecklistItemEditable()).isTrue();

        // 응답의 목록과 다음 상세 조회의 목록이 같아야 화면이 흔들리지 않는다
        assertThat(detailOf(subWorkId).checklist()).hasSize(5);
    }

    /*
     * 삭제가 하드라 다음 번호를 count로 매기면 겹친다. max + 1이어야 한다 — 남은 항목의
     * sort_seq를 다시 매기지 않기로 한 결정과 짝이다.
     */
    @Test
    void addChecklistItemNumbersFromMaxNotCount() {
        Long subWorkId = createSubWork(approvalFreeTypeId);
        deleteChecklistItem(subWorkId, checklistItemIds(subWorkId).get(1));

        SubWorkChecklistMutationResponse response = addChecklistItem(subWorkId, "현장 답사");

        assertThat(response.checklist()).hasSize(4);
        assertThat(response.item().sortOrder()).isEqualTo(5);
        assertThat(response.checklist())
                .extracting(SubWorkChecklistItemResponse::sortOrder)
                .containsExactly(1, 3, 4, 5);
    }

    // 문구를 고쳐도 체크 상태는 그대로다 — 다듬는 것과 해낸 것은 다른 사실이다
    @Test
    void updateChecklistItemArticleKeepsCompletion() {
        Long subWorkId = createSubWork(approvalFreeTypeId);
        Long firstItemId = checklistItemIds(subWorkId).get(0);
        updateChecklistItem(subWorkId, firstItemId, true);

        SubWorkChecklistMutationResponse response =
                updateChecklistItemArticle(subWorkId, firstItemId, "장소 후보 5곳 리스트업");

        assertThat(response.item().article()).isEqualTo("장소 후보 5곳 리스트업");
        assertThat(response.item().isCompleted()).isTrue();
        assertThat(response.item().sortOrder()).isEqualTo(1);
        assertThat(response.checklistSummary().completedCount()).isEqualTo(1);
        assertThat(response.checklistSummary().totalCount()).isEqualTo(4);
    }

    // 체크되지 않은 항목은 지워진다. 지우는 행위 자체가 "이번 건엔 해당 없다"는 선언이다
    @Test
    void deleteUncheckedChecklistItemRemovesItFromTheList() {
        Long subWorkId = createSubWork(approvalFreeTypeId);
        List<Long> itemIds = checklistItemIds(subWorkId);

        SubWorkChecklistMutationResponse response = deleteChecklistItem(subWorkId, itemIds.get(1));

        // 지운 항목의 마지막 모습이 응답에 남는다 — 지운 뒤에는 다시 물을 곳이 없다
        assertThat(response.item().checklistItemId()).isEqualTo(itemIds.get(1));
        assertThat(response.checklist()).hasSize(3);
        assertThat(response.checklist())
                .extracting(SubWorkChecklistItemResponse::checklistItemId)
                .doesNotContain(itemIds.get(1));
        assertThat(response.checklistSummary().totalCount()).isEqualTo(3);
        assertThat(detailOf(subWorkId).checklist()).hasSize(3);
    }

    /*
     * 지우면 완료 조건이 짧아진다 — 그것이 이 API의 뜻이고, 그래서 상태·체크 두 잠금과
     * 이력이 함께 붙은 것이다. 남은 세 항목만 체크하면 완료 승인이 통과한다.
     */
    @Test
    void deletingAnItemLowersTheBarForCompletion() {
        Long subWorkId = createSubWork(approvalFreeTypeId);
        deleteChecklistItem(subWorkId, checklistItemIds(subWorkId).get(3));
        transition(subWorkId, TransitionAction.START, null);
        completeChecklist(subWorkId);
        transition(subWorkId, TransitionAction.REQUEST_REVIEW, null);

        SubWorkTransitionResponse response =
                transition(subWorkId, TransitionAction.APPROVE_COMPLETE, null);

        assertThat(response.workStatus()).isEqualTo(WorkStatus.DONE);
        assertThat(detailOf(subWorkId).checklistSummary().totalCount()).isEqualTo(3);
    }

    /*
     * **체크된 항목은 지울 수 없다** (ssccops#255 결정). "해당 없음으로 지우기"와
     * "안 하고 지우기"가 화면에서 구별되지 않기 때문이다. 상태 잠금과 다른 코드로 거절하는 이유는
     * 해소 방법이 다르기 때문이다 — 이쪽은 체크를 푸는 순간 지울 수 있다.
     */
    @Test
    void deleteCheckedChecklistItemIsRejected() {
        Long subWorkId = createSubWork(approvalFreeTypeId);
        Long firstItemId = checklistItemIds(subWorkId).get(0);
        updateChecklistItem(subWorkId, firstItemId, true);

        assertThatThrownBy(() -> deleteChecklistItem(subWorkId, firstItemId))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.CHECKLIST_ITEM_COMPLETED);
        assertThat(detailOf(subWorkId).checklist()).hasSize(4);

        // 체크를 풀면 지워진다 — 거절이 영구적인 금지가 아니라는 것이 코드를 가른 근거다
        updateChecklistItem(subWorkId, firstItemId, false);
        assertThat(deleteChecklistItem(subWorkId, firstItemId).checklist()).hasSize(3);
    }

    /*
     * **검토부터 항목 편집이 잠긴다** (ssccops#255 결정). 체크·해제는 그대로 된다 —
     * 두 기준이 갈리는 유일한 상태라 여기서 함께 본다.
     */
    @Test
    void checklistItemsAreLockedFromReview() {
        Long subWorkId = subWorkInReview(approvalFreeTypeId);
        Long firstItemId = checklistItemIds(subWorkId).get(0);

        assertThat(detailOf(subWorkId).isChecklistItemEditable()).isFalse();
        assertThatThrownBy(() -> addChecklistItem(subWorkId, "현장 답사"))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.TRANSITION_NOT_ALLOWED);
        assertThatThrownBy(() -> updateChecklistItemArticle(subWorkId, firstItemId, "바꾸기"))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.TRANSITION_NOT_ALLOWED);
        assertThatThrownBy(() -> deleteChecklistItem(subWorkId, firstItemId))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.TRANSITION_NOT_ALLOWED);

        // 체크는 검토에서도 된다 — 두 잠금을 같은 것으로 묶지 않았다는 증거다
        assertThat(updateChecklistItem(subWorkId, firstItemId, true).item().isCompleted()).isTrue();
    }

    // 반려로 진행에 되돌아오면 다시 열린다 — '계획을 고쳐 다시 올린다'의 뜻이다
    @Test
    void checklistItemsReopenAfterRejection() {
        Long subWorkId = subWorkInReview(approvalNeededTypeId);
        transition(subWorkId, TransitionAction.REJECT, "현장 답사 결과 누락");

        assertThat(detailOf(subWorkId).isChecklistItemEditable()).isTrue();
        assertThat(addChecklistItem(subWorkId, "현장 답사").checklist()).hasSize(5);
    }

    /*
     * **이 이슈의 가장 중요한 항목.** 두 잠금이 있어도 기획·진행 단계의 삭제는 여전히
     * 가능하고 정당하다. 남지 않으면 "이 업무는 왜 점검 항목이 셋뿐이었나"에 답할 수 없다.
     *
     * 하드로 지워도 지워진 항목의 문구가 이력에 남는다 — 소프트 삭제를 고르지 않은 근거다.
     */
    @Test
    void everyItemChangeIsRecordedInHistory() {
        Long subWorkId = createSubWork(approvalFreeTypeId);
        List<Long> itemIds = checklistItemIds(subWorkId);
        Long addedItemId = addChecklistItem(subWorkId, "현장 답사").item().checklistItemId();
        updateChecklistItemArticle(subWorkId, addedItemId, "현장 답사 및 사진 촬영");
        String removedArticle = detailOf(subWorkId).checklist().get(3).article();
        deleteChecklistItem(subWorkId, itemIds.get(3));

        List<SubWorkChecklistHistoryResponse> history = historyOf(subWorkId);

        assertThat(history)
                .extracting(
                        SubWorkChecklistHistoryResponse::changeType,
                        SubWorkChecklistHistoryResponse::previousArticle,
                        SubWorkChecklistHistoryResponse::nextArticle)
                .containsExactly(
                        tuple(ChecklistChangeType.ADDED, null, "현장 답사"),
                        tuple(ChecklistChangeType.MODIFIED, "현장 답사", "현장 답사 및 사진 촬영"),
                        tuple(ChecklistChangeType.REMOVED, removedArticle, null));
        assertThat(history)
                .allSatisfy(
                        row -> {
                            assertThat(row.performer().memberId()).isEqualTo(registrant.getId());
                            assertThat(row.changedAt()).isEqualTo(NOW);
                        });
        // 지워진 항목의 식별자도 남는다 — 그 행은 이미 없지만 이력은 가리킨다
        assertThat(history.get(2).checklistItemId()).isEqualTo(itemIds.get(3));
    }

    // 체크·해제는 이 이력에 남지 않는다 — 진척 기록이 완료 조건의 변경을 묻어 버리지 않게 한다
    @Test
    void togglingAnItemLeavesNoChecklistHistory() {
        Long subWorkId = createSubWork(approvalFreeTypeId);
        completeChecklist(subWorkId);

        assertThat(historyOf(subWorkId)).isEmpty();
    }

    /*
     * 항목 편집은 상태 전이가 아니다. 스테퍼(업무 상태)·승인 칩이 그대로여야 하고
     * sub_work_stts_hstry에도 아무것도 들어가지 않는다 — 이력이 둘로 나뉘어 있는 이유다.
     */
    @Test
    void itemChangesDoNotTouchStatusesOrStatusHistory() {
        Long subWorkId = createSubWork(approvalNeededTypeId);

        addChecklistItem(subWorkId, "현장 답사");

        SubWorkDetailResponse detail = detailOf(subWorkId);
        assertThat(detail.workStatus()).isEqualTo(WorkStatus.PLANNING);
        assertThat(detail.approvalStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(subWorkStatusHistoryRepository.count()).isZero();
    }

    // 경로의 하위 업무에 속하지 않는 항목은 고치거나 지울 수 없다 (IDOR) — 403이 아니라 404다
    @Test
    void editingAnItemOfAnotherSubWorkIsRejected() {
        Long subWorkId = createSubWork(approvalFreeTypeId);
        Long otherSubWorkId = createSubWork(approvalFreeTypeId);
        Long otherItemId = checklistItemIds(otherSubWorkId).get(0);

        assertThatThrownBy(() -> updateChecklistItemArticle(subWorkId, otherItemId, "바꾸기"))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.CHECKLIST_ITEM_NOT_FOUND);
        assertThatThrownBy(() -> deleteChecklistItem(subWorkId, otherItemId))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.CHECKLIST_ITEM_NOT_FOUND);
        assertThat(detailOf(otherSubWorkId).checklist()).hasSize(4);
    }

    /*
     * 유형(sub_work_type)의 원본 목록은 건드리지 않는다 (POL-005). 여기서 더하고 지운 것은
     * 이 하위 업무의 것이고, 다음에 같은 유형으로 등록되는 건은 원본 네 항목으로 시작한다.
     */
    @Test
    void itemChangesDoNotLeakIntoTheType() {
        Long subWorkId = createSubWork(approvalFreeTypeId);
        addChecklistItem(subWorkId, "현장 답사");
        deleteChecklistItem(subWorkId, checklistItemIds(subWorkId).get(0));

        Long nextSubWorkId = createSubWork(approvalFreeTypeId);

        assertThat(detailOf(nextSubWorkId).checklist()).hasSize(4);
        assertThat(
                        subWorkTypeRepository
                                .findById(approvalFreeTypeId)
                                .orElseThrow()
                                .completionCheckArticles())
                .hasSize(4);
    }

    /*
     * 항목마다의 isDeletable은 **두 잠금을 합친 값**이다 (#307) — 화면이 "편집 가능 상태이고
     * 체크 안 됨"을 직접 엮지 않게 한다. 같은 목록 안에서 항목마다 갈리고, 검토부터는 전부 false다.
     */
    @Test
    void itemDeletabilityCombinesBothLocks() {
        Long subWorkId = createSubWork(approvalFreeTypeId);
        List<Long> itemIds = checklistItemIds(subWorkId);
        updateChecklistItem(subWorkId, itemIds.get(0), true);

        assertThat(detailOf(subWorkId).checklist())
                .extracting(
                        SubWorkChecklistItemResponse::isCompleted,
                        SubWorkChecklistItemResponse::isDeletable)
                .containsExactly(
                        tuple(true, false),
                        tuple(false, true),
                        tuple(false, true),
                        tuple(false, true));

        // 체크를 풀면 지울 수 있게 된다 — 체크 응답이 그 전환을 그 자리에서 알려 준다
        assertThat(updateChecklistItem(subWorkId, itemIds.get(0), false).item().isDeletable())
                .isTrue();
    }

    // 검토부터는 체크 안 된 항목도 지울 수 없다 — 상태 잠금이 항목 상태보다 앞선다
    @Test
    void nothingIsDeletableFromReview() {
        Long subWorkId = subWorkInReview(approvalFreeTypeId);

        assertThat(detailOf(subWorkId).checklist())
                .extracting(SubWorkChecklistItemResponse::isDeletable)
                .containsOnly(false);
    }

    private SubWorkChecklistMutationResponse addChecklistItem(Long subWorkId, String article) {
        entityManager.flush();
        entityManager.clear();
        return subWorkService.addChecklistItem(
                subWorkId, new SubWorkChecklistItemSaveRequest(article), registrant);
    }

    private SubWorkChecklistMutationResponse updateChecklistItemArticle(
            Long subWorkId, Long checklistItemId, String article) {
        entityManager.flush();
        entityManager.clear();
        return subWorkService.updateChecklistItemArticle(
                subWorkId,
                checklistItemId,
                new SubWorkChecklistItemSaveRequest(article),
                registrant);
    }

    private SubWorkChecklistMutationResponse deleteChecklistItem(
            Long subWorkId, Long checklistItemId) {
        entityManager.flush();
        entityManager.clear();
        return subWorkService.deleteChecklistItem(subWorkId, checklistItemId, registrant);
    }

    private List<SubWorkChecklistHistoryResponse> historyOf(Long subWorkId) {
        entityManager.flush();
        entityManager.clear();
        return subWorkService.getChecklistHistory(subWorkId);
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
        return createWithDueAt(dueAt).subWorkId();
    }

    private SubWorkCreateResponse createWithDueAt(OffsetDateTime dueAt) {
        return subWorkService.createSubWork(
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
                registrant);
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
