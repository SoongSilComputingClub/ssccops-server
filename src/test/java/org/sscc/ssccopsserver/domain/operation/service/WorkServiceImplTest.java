package org.sscc.ssccopsserver.domain.operation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.domain.member.service.MemberService;
import org.sscc.ssccopsserver.domain.member.service.MemberServiceImpl;
import org.sscc.ssccopsserver.domain.operation.code.error.OperationErrorCode;
import org.sscc.ssccopsserver.domain.operation.dto.WorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.WorkCreateResponse;
import org.sscc.ssccopsserver.domain.operation.entity.OperationEntity;
import org.sscc.ssccopsserver.domain.operation.entity.OperationPriority;
import org.sscc.ssccopsserver.domain.operation.entity.OperationType;
import org.sscc.ssccopsserver.domain.operation.entity.WorkStatus;
import org.sscc.ssccopsserver.domain.operation.entity.WorkType;
import org.sscc.ssccopsserver.domain.operation.repository.OperationRepository;
import org.sscc.ssccopsserver.domain.operation.repository.WorkRepository;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

@DataJpaTest
@ActiveProfiles("test")
class WorkServiceImplTest {

    private static final OffsetDateTime START =
            OffsetDateTime.of(2026, 9, 1, 18, 0, 0, 0, ZoneOffset.ofHours(9));
    private static final OffsetDateTime END = START.plusHours(2);

    @Autowired private OperationRepository operationRepository;
    @Autowired private WorkRepository workRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;

    private WorkService workService;
    private Long ownerId;

    @BeforeEach
    void setUp() {
        MemberService memberService =
                new MemberServiceImpl(
                        memberRepository, memberGradeRepository, memberStatusRepository);
        workService = new WorkServiceImpl(operationRepository, workRepository, memberService);

        MemberEntity owner =
                memberService.findOrProvisionBySpbUserId(UUID.randomUUID(), "owner@sscc.org");
        ownerId = owner.getId();
    }

    @Test
    void createWorkPersistsOperationAndWork() {
        WorkCreateRequest request =
                new WorkCreateRequest(
                        "2026 신입생 환영회", WorkType.EVENT, ownerId, START, END, null, null);

        WorkCreateResponse response = workService.createWork(request);

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
    }

    @Test
    void createWorkFixesStatusAndProgressRateOnServer() {
        WorkCreateResponse response =
                workService.createWork(
                        new WorkCreateRequest(
                                "정기 총회", WorkType.REGULAR, ownerId, null, null, null, null));

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
                                "부스 위치 선정이 늦었다"));

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
                                "우선순위 미지정", WorkType.ROUTINE, ownerId, null, null, null, null));

        assertThat(response.priority()).isEqualTo(OperationPriority.NORMAL);
        assertThat(response.review()).isNull();
    }

    @Test
    void createWorkWithUnknownOwnerIsRejected() {
        WorkCreateRequest request =
                new WorkCreateRequest(
                        "담당자 없는 업무", WorkType.EVENT, ownerId + 999, START, END, null, null);

        assertThatThrownBy(() -> workService.createWork(request))
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

        assertThatThrownBy(() -> workService.createWork(request))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.INVALID_OPERATION_PERIOD);

        assertThat(operationRepository.count()).isZero();
    }
}
