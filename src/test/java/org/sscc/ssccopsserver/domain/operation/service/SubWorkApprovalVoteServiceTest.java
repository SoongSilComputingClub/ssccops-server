package org.sscc.ssccopsserver.domain.operation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.sscc.ssccopsserver.domain.operation.code.error.OperationErrorCode;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkChecklistItemUpdateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkDetailResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkTransitionRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkTransitionResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkVoteRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkVoteResponse;
import org.sscc.ssccopsserver.domain.operation.dto.WorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.entity.ApprovalStatus;
import org.sscc.ssccopsserver.domain.operation.entity.OperationPriority;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkChecklistItemEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkTypeEntity;
import org.sscc.ssccopsserver.domain.operation.entity.TransitionAction;
import org.sscc.ssccopsserver.domain.operation.entity.VoteChoice;
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
 * 찬반 투표와 승인·반려 권한 (#47 · OPS-014·OPS-015).
 *
 * 검증하는 규칙은 셋이다.
 *  1. 승인자 자격을 가진 운영자만 승인·반려할 수 있다.
 *  2. 정족수 유형은 찬성이 모인 뒤에야 승인할 수 있고, 반려는 정족수와 무관하다.
 *  3. 사전에 운영진 권한을 가진 운영자는 누구나 찬반 투표를 할 수 있다.
 */
@DataJpaTest
// AuthorityPolicy는 @Service라 @DataJpaTest 슬라이스에 없다. MemberServiceImpl이 프로필의
// capabilities를 계산하는 데 쓰므로(#9) 정책과 그 Clock만 슬라이스에 들여온다.
@Import({JpaAuditingConfig.class, AuthorityPolicy.class, ClockConfig.class})
@ActiveProfiles("test")
class SubWorkApprovalVoteServiceTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final OffsetDateTime START = OffsetDateTime.of(2026, 9, 1, 18, 0, 0, 0, KST);
    private static final OffsetDateTime END = START.plusHours(2);
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 8, 20, 12, 0, 0, 0, KST);
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW.toInstant(), KST);

    // 정족수 유형이 요구하는 찬성 수. 1과 2는 규칙이 다르므로(1도 단독과 다르다) 2로 둔다
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

    private MemberEntity registrant; // 등록자. 역할이 없어 승인도 투표도 할 수 없다
    private MemberEntity president; // 회장 — 정족수 유형(대외공지)의 승인자
    private MemberEntity vicePresident; // 부회장 — 회장이 아니므로 승인자가 아니다
    private MemberEntity treasurer; // 총무 — 단독 유형(예산지출)의 승인자
    private MemberEntity staff; // 기획국원 — 승인자는 아니지만 투표는 할 수 있다
    private MemberEntity studyLeader; // 스터디장 — 운영진이 아니라 투표도 못 한다

    private Long parentWorkId;
    private Long quorumTypeId; // 승인 필요 + 정족수 2
    private Long soleTypeId; // 승인 필요 + 단독
    private Long approvalFreeTypeId; // 승인 불필요

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
                        new SubWorkOwnershipPolicy(authorityPolicy),
                        FIXED_CLOCK,
                        entityManager.getEntityManager());

        registrant = saveMember("20200001", "김도현", null);
        president = saveMember("20200002", "백승우", MemberRoleFixture.PRESIDENT);
        vicePresident = saveMember("20200003", "원영진", "부회장");
        treasurer = saveMember("20200004", "이서연", MemberRoleFixture.TREASURER);
        staff = saveMember("20200005", "박지훈", MemberRoleFixture.PLANNING_STAFF);
        studyLeader = saveMember("20200006", "정하늘", MemberRoleFixture.STUDY_LEADER);

        /*
         * 시드에는 정족수 유형이 없다(4종 모두 단독). 승인자가 회장인 '대외공지'를 정족수로
         * 바꿔 쓴다 — 유형 관리 화면(#43)에서 의사결정을 '정족수'로 바꾼 것과 같은 상태다.
         */
        SubWorkTypeEntity quorumType =
                SubWorkTypeFixture.entityOf(subWorkTypeRepository, SubWorkTypeFixture.ANNOUNCEMENT);
        quorumType.update(
                quorumType.getTypeName(),
                true,
                "PRESIDENT",
                true,
                REQUIRED_AGREE_COUNT,
                List.of("공지 문안 검수"));
        quorumTypeId = quorumType.getId();
        soleTypeId = SubWorkTypeFixture.idOf(subWorkTypeRepository, SubWorkTypeFixture.EXPENDITURE);
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

    // 정족수가 모이기 전에는 승인자라도 최종 승인할 수 없다 (TR-03 선행 조건)
    @Test
    void approveBeforeQuorumIsRejected() {
        Long subWorkId = subWorkInReview(quorumTypeId);
        completeChecklist(subWorkId);
        vote(subWorkId, VoteChoice.AGREE, staff);

        assertThatThrownBy(
                        () ->
                                transition(
                                        subWorkId,
                                        TransitionAction.APPROVE_COMPLETE,
                                        null,
                                        president))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.QUORUM_NOT_MET);
        assertThat(detailWorkStatus(subWorkId)).isEqualTo(WorkStatus.REVIEW);
    }

    // 찬성이 다 모이면 승인자가 최종 승인할 수 있다
    @Test
    void approveAfterQuorumSucceeds() {
        Long subWorkId = subWorkInReview(quorumTypeId);
        completeChecklist(subWorkId);
        vote(subWorkId, VoteChoice.AGREE, staff);
        SubWorkVoteResponse second = vote(subWorkId, VoteChoice.AGREE, treasurer);

        assertThat(second.met()).isTrue();
        assertThat(second.currentCount()).isEqualTo(2);
        assertThat(second.requiredCount()).isEqualTo(REQUIRED_AGREE_COUNT);

        SubWorkTransitionResponse response =
                transition(subWorkId, TransitionAction.APPROVE_COMPLETE, null, president);

        assertThat(response.workStatus()).isEqualTo(WorkStatus.DONE);
        assertThat(response.approvalStatus()).isEqualTo(ApprovalStatus.APPROVED);
    }

    /*
     * 정족수는 승인자를 대체하지 않는다 (POL-007 O-03). 찬성이 다 모여도 승인자가 아닌 사람이
     * 누르면 완료되지 않는다 — 이 검사가 없으면 전이 API를 부를 수 있는 누구든 승인이 된다.
     */
    @Test
    void quorumDoesNotLetNonApproverComplete() {
        Long subWorkId = subWorkInReview(quorumTypeId);
        completeChecklist(subWorkId);
        vote(subWorkId, VoteChoice.AGREE, staff);
        vote(subWorkId, VoteChoice.AGREE, treasurer);

        assertThatThrownBy(
                        () -> transition(subWorkId, TransitionAction.APPROVE_COMPLETE, null, staff))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.FORBIDDEN);
        assertThat(detailWorkStatus(subWorkId)).isEqualTo(WorkStatus.REVIEW);
    }

    // 부회장은 회장이 아니다. 역할명이 '회장'으로 끝난다고 승인자가 되어서는 안 된다
    @Test
    void vicePresidentIsNotPresident() {
        Long subWorkId = subWorkInReview(quorumTypeId);
        completeChecklist(subWorkId);
        vote(subWorkId, VoteChoice.AGREE, staff);
        vote(subWorkId, VoteChoice.AGREE, treasurer);

        assertThatThrownBy(
                        () ->
                                transition(
                                        subWorkId,
                                        TransitionAction.APPROVE_COMPLETE,
                                        null,
                                        vicePresident))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.FORBIDDEN);
    }

    /*
     * 국장은 부서별로 나뉜다(홍보국장·행정국장 …). 유형이 지정하는 승인자 코드는 DIRECTOR 하나뿐이라
     * 부서명이 앞에 붙은 역할도 승인자로 인정돼야 한다 — 아니면 국장 승인 유형을 아무도 승인할 수 없다.
     */
    @Test
    void departmentalDirectorIsRecognizedAsAuthorizer() {
        SubWorkTypeEntity soleType =
                SubWorkTypeFixture.entityOf(subWorkTypeRepository, SubWorkTypeFixture.EXPENDITURE);
        soleType.update(soleType.getTypeName(), true, "DIRECTOR", false, null, List.of("영수증 첨부"));
        MemberEntity prDirector = saveMember("20200007", "최유진", MemberRoleFixture.PR_DIRECTOR);

        Long subWorkId = subWorkInReview(soleTypeId);
        completeChecklist(subWorkId);

        SubWorkTransitionResponse response =
                transition(subWorkId, TransitionAction.APPROVE_COMPLETE, null, prDirector);

        assertThat(response.workStatus()).isEqualTo(WorkStatus.DONE);
    }

    // 단독 유형은 투표 없이 승인자의 승인 한 번으로 끝난다
    @Test
    void soleDecisionTypeNeedsNoVote() {
        Long subWorkId = subWorkInReview(soleTypeId);
        completeChecklist(subWorkId);

        SubWorkTransitionResponse response =
                transition(subWorkId, TransitionAction.APPROVE_COMPLETE, null, treasurer);

        assertThat(response.workStatus()).isEqualTo(WorkStatus.DONE);
        assertThat(response.approvalStatus()).isEqualTo(ApprovalStatus.APPROVED);
    }

    // 단독 유형에는 셀 대상이 없다. 승인함 화면이 모든 카드에 찬반 버튼을 그리더라도 서버가 막는다
    @Test
    void voteOnSoleDecisionTypeIsRejected() {
        Long subWorkId = subWorkInReview(soleTypeId);

        assertThatThrownBy(() -> vote(subWorkId, VoteChoice.AGREE, staff))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.TRANSITION_NOT_ALLOWED);
    }

    // 검토 단계로 올라오기 전에는 투표할 수 없다
    @Test
    void voteBeforeReviewIsRejected() {
        Long subWorkId = createSubWork(quorumTypeId);

        assertThatThrownBy(() -> vote(subWorkId, VoteChoice.AGREE, staff))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.TRANSITION_NOT_ALLOWED);
    }

    // 사전에 운영진 권한을 가진 운영자는 누구나 투표할 수 있다 — 국원도 승인자도 마찬가지다
    @Test
    void anyStaffMemberCanVote() {
        Long subWorkId = subWorkInReview(quorumTypeId);

        assertThat(vote(subWorkId, VoteChoice.AGREE, staff).currentCount()).isEqualTo(1);
        assertThat(vote(subWorkId, VoteChoice.AGREE, president).currentCount()).isEqualTo(2);
    }

    // 운영진이 아닌 회원은 투표할 수 없다
    @Test
    void nonStaffMemberCannotVote() {
        Long subWorkId = subWorkInReview(quorumTypeId);

        assertThatThrownBy(() -> vote(subWorkId, VoteChoice.AGREE, studyLeader))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> vote(subWorkId, VoteChoice.AGREE, registrant))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.FORBIDDEN);
    }

    // 1인 1표. 다시 던지면 표가 늘지 않고 바뀐다 — 찬성을 반대로 돌리면 집계도 줄어든다
    @Test
    void votingAgainReplacesPreviousVote() {
        Long subWorkId = subWorkInReview(quorumTypeId);
        vote(subWorkId, VoteChoice.AGREE, staff);

        SubWorkVoteResponse changed = vote(subWorkId, VoteChoice.DISAGREE, staff);

        assertThat(changed.myVote()).isEqualTo(VoteChoice.DISAGREE);
        assertThat(changed.currentCount()).isZero();
        assertThat(subWorkApprovalVoteRepository.findAll()).hasSize(1);
    }

    // 반대는 세지 않는다. 반대만 모여도 상태는 그대로이고 자동 반려도 일어나지 않는다
    @Test
    void disagreementDoesNotChangeStatus() {
        Long subWorkId = subWorkInReview(quorumTypeId);

        SubWorkVoteResponse response = vote(subWorkId, VoteChoice.DISAGREE, staff);

        assertThat(response.met()).isFalse();
        assertThat(response.currentCount()).isZero();
        assertThat(detailWorkStatus(subWorkId)).isEqualTo(WorkStatus.REVIEW);
        assertThat(detailApprovalStatus(subWorkId)).isEqualTo(ApprovalStatus.PENDING);
    }

    /*
     * 반려는 정족수와 무관하게 언제든 할 수 있다 — 다만 사유가 필수다.
     * 찬성이 하나도 없어도 승인자는 바로 반려할 수 있어야 한다.
     */
    @Test
    void rejectNeedsNoQuorumButNeedsReason() {
        Long subWorkId = subWorkInReview(quorumTypeId);

        assertThatThrownBy(() -> transition(subWorkId, TransitionAction.REJECT, "  ", president))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.REASON_REQUIRED);

        SubWorkTransitionResponse response =
                transition(subWorkId, TransitionAction.REJECT, "공지 문안 재검토 필요", president);

        assertThat(response.workStatus()).isEqualTo(WorkStatus.IN_PROGRESS);
        assertThat(response.approvalStatus()).isEqualTo(ApprovalStatus.REJECTED);
    }

    // 반려도 승인자의 권한이다 (TR-04 수행 권한). 운영진이라고 아무나 반려할 수 없다
    @Test
    void rejectByNonApproverIsRejected() {
        Long subWorkId = subWorkInReview(quorumTypeId);

        assertThatThrownBy(() -> transition(subWorkId, TransitionAction.REJECT, "사유", staff))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.FORBIDDEN);
        assertThat(detailWorkStatus(subWorkId)).isEqualTo(WorkStatus.REVIEW);
    }

    /*
     * 반려 후 보완해 다시 올라오면 회차가 바뀐다. 이전 회차의 찬성은 새 계획에 대한 동의가
     * 아니므로 집계되지 않는다 — 표를 지우지 않고도(POL-004 이력 불변) 초기화되어야 한다.
     */
    @Test
    void votesDoNotCarryOverToNextApprovalRound() {
        Long subWorkId = subWorkInReview(quorumTypeId);
        completeChecklist(subWorkId);
        vote(subWorkId, VoteChoice.AGREE, staff);
        vote(subWorkId, VoteChoice.AGREE, treasurer);
        transition(subWorkId, TransitionAction.REJECT, "공지 문안 재검토 필요", president);
        transition(subWorkId, TransitionAction.REQUEST_REVIEW, null, registrant);

        assertThatThrownBy(
                        () ->
                                transition(
                                        subWorkId,
                                        TransitionAction.APPROVE_COMPLETE,
                                        null,
                                        president))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.QUORUM_NOT_MET);

        // 이전 회차의 표는 남아 있고, 새 회차의 표만 새로 쌓인다
        SubWorkVoteResponse newRound = vote(subWorkId, VoteChoice.AGREE, staff);
        assertThat(newRound.approvalSequence()).isEqualTo(2);
        assertThat(newRound.currentCount()).isEqualTo(1);
        assertThat(subWorkApprovalVoteRepository.findAll()).hasSize(3);
    }

    /*
     * 승인이 필요 없는 유형(REQ-016 저위험 면제)은 승인자 검사를 하지 않는다.
     * 여기서 막으면 저위험 업무를 아무도 완료할 수 없게 된다.
     */
    @Test
    void approvalFreeTypeNeedsNoApproverRole() {
        Long subWorkId = subWorkInReview(approvalFreeTypeId);
        completeChecklist(subWorkId);

        assertThatCode(
                        () ->
                                transition(
                                        subWorkId,
                                        TransitionAction.APPROVE_COMPLETE,
                                        null,
                                        registrant))
                .doesNotThrowAnyException();
        assertThat(detailApprovalStatus(subWorkId)).isEqualTo(ApprovalStatus.NOT_REQUIRED);
    }

    /*
     * 상세 화면이 승인·반려 버튼을 그릴 수 있어야 한다 (#58). 판정을 프론트가 역할명으로
     * 복제하면 서버의 승인자 판정과 갈리므로, 누가 누를 수 있는지는 서버가 답한다.
     */
    @Test
    void detailAnswersWhoCanApproveAndReject() {
        Long subWorkId = subWorkInReview(quorumTypeId); // 승인자가 회장인 유형

        SubWorkDetailResponse forApprover = detail(subWorkId, president);
        assertThat(forApprover.canApprove()).isTrue();
        assertThat(forApprover.canReject()).isTrue();

        // 총무도 국원도 이 유형의 승인자가 아니다 — 운영진이라는 것과 승인자라는 것은 다르다
        assertThat(detail(subWorkId, treasurer).canApprove()).isFalse();
        assertThat(detail(subWorkId, staff).canReject()).isFalse();
    }

    /*
     * 승인 단계가 없는 유형은 승인자 판정이 아니라 담당자 판정을 쓴다(#101) — 그 유형의
     * 완료·반려는 담당자(또는 WORK_MANAGE 보유자)의 몫이지 "전이가 통과시키는 사람 누구나"가
     * 아니다. 담당자(registrant)에게는 버튼이 보이고, 담당자도 운영진도 아닌 스터디장에게는
     * 보이지 않는다 — 실제 전이 게이트(SubWorkOwnershipPolicy)와 같은 판정이어야 버튼은
     * 보이는데 누르면 403이 나는 자리가 생기지 않는다.
     */
    @Test
    void detailAllowsOwnerButNotUnrelatedMemberToDecideOnApprovalFreeType() {
        Long subWorkId = subWorkInReview(approvalFreeTypeId);

        SubWorkDetailResponse forOwner = detail(subWorkId, registrant);
        assertThat(forOwner.canApprove()).isTrue();
        assertThat(forOwner.canReject()).isTrue();

        SubWorkDetailResponse forOutsider = detail(subWorkId, studyLeader);
        assertThat(forOutsider.canApprove()).isFalse();
        assertThat(forOutsider.canReject()).isFalse();
    }

    // 승인함 카드와 같은 정족수 진행을 상세에서도 본다 — 시안은 여기서 승인을 누른다
    @Test
    void detailCarriesQuorumProgressAndMyVote() {
        Long subWorkId = subWorkInReview(quorumTypeId);
        vote(subWorkId, VoteChoice.AGREE, staff);

        SubWorkDetailResponse forVoter = detail(subWorkId, staff);
        assertThat(forVoter.quorum().needed()).isTrue();
        assertThat(forVoter.quorum().requiredCount()).isEqualTo(REQUIRED_AGREE_COUNT);
        assertThat(forVoter.quorum().currentCount()).isEqualTo(1L);
        assertThat(forVoter.quorum().met()).isFalse();
        assertThat(forVoter.myVote()).isEqualTo(VoteChoice.AGREE);

        // 내 표는 보는 사람마다 다르다. 아직 던지지 않았으면 버튼이 선택되지 않은 상태다
        assertThat(detail(subWorkId, president).myVote()).isNull();
    }

    /*
     * 반려 후 다시 올라오면 회차가 바뀌므로 이전 회차에 던진 표는 이번 선택 상태가 아니다.
     * 승인함 카드가 회차로 거르는 것과 같은 규칙이 상세에도 있어야 한다.
     */
    @Test
    void detailDropsVoteFromPreviousApprovalRound() {
        Long subWorkId = subWorkInReview(quorumTypeId);
        vote(subWorkId, VoteChoice.AGREE, staff);
        transition(subWorkId, TransitionAction.REJECT, "공지 문안 재검토 필요", president);
        transition(subWorkId, TransitionAction.REQUEST_REVIEW, null, registrant);

        SubWorkDetailResponse detail = detail(subWorkId, staff);

        assertThat(detail.myVote()).isNull();
        assertThat(detail.quorum().currentCount()).isZero();
    }

    // 단독 유형은 셀 표가 없다. 0으로 채우면 '아무도 찬성하지 않은 정족수 유형'과 구별되지 않는다
    @Test
    void detailMarksQuorumNotNeededForSoleType() {
        Long subWorkId = subWorkInReview(soleTypeId);

        SubWorkDetailResponse detail = detail(subWorkId, treasurer);

        assertThat(detail.quorum().needed()).isFalse();
        assertThat(detail.quorum().requiredCount()).isNull();
        assertThat(detail.quorum().currentCount()).isNull();
        assertThat(detail.quorum().met()).isNull();
        assertThat(detail.myVote()).isNull();
    }

    /*
     * 반려 모달이 "사유는 요청자에게 전달됩니다"라고 약속한다 (#58). 알림 채널이 없는 지금
     * 그 전달의 실체는 상세 응답이며, 없으면 진행으로 되돌아온 담당자가 무엇을 고칠지 알 수 없다.
     */
    @Test
    void detailCarriesLatestRejectionReason() {
        Long subWorkId = subWorkInReview(soleTypeId);
        transition(subWorkId, TransitionAction.REJECT, "예산안 대비 초과, 견적서 재첨부 필요", treasurer);

        SubWorkDetailResponse detail = detail(subWorkId, registrant);

        assertThat(detail.latestRejection()).isNotNull();
        assertThat(detail.latestRejection().reason()).isEqualTo("예산안 대비 초과, 견적서 재첨부 필요");
        assertThat(detail.latestRejection().rejector().memberId()).isEqualTo(treasurer.getId());
        assertThat(detail.latestRejection().rejectedAt()).isNotNull();
    }

    // 반려 → 보완 → 재검토요청 → 재반려. 화면이 묻는 것은 '직전에 왜 반려됐는가' 하나다
    @Test
    void detailKeepsOnlyTheMostRecentRejection() {
        Long subWorkId = subWorkInReview(soleTypeId);
        transition(subWorkId, TransitionAction.REJECT, "첫 번째 반려 사유", treasurer);
        transition(subWorkId, TransitionAction.REQUEST_REVIEW, null, registrant);
        transition(subWorkId, TransitionAction.REJECT, "두 번째 반려 사유", treasurer);

        assertThat(detail(subWorkId, registrant).latestRejection().reason())
                .isEqualTo("두 번째 반려 사유");
        // 이력 자체는 지우지 않는다 (POL-004)
        assertThat(subWorkRejectionRepository.findAll()).hasSize(2);
    }

    // 반려된 적이 없으면 보여줄 사유도 없다
    @Test
    void detailHasNoRejectionBeforeAnyRejection() {
        assertThat(detail(subWorkInReview(soleTypeId), registrant).latestRejection()).isNull();
    }

    private SubWorkDetailResponse detail(Long subWorkId, MemberEntity viewer) {
        entityManager.flush();
        entityManager.clear();
        return subWorkService.getSubWork(subWorkId, viewer);
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

    private Long createSubWork(long subWorkTypeId) {
        return subWorkService
                .createSubWork(
                        new SubWorkCreateRequest(
                                parentWorkId,
                                "9월 신입 모집 포스터",
                                subWorkTypeId,
                                registrant.getId(),
                                START,
                                END,
                                END,
                                OperationPriority.NORMAL,
                                "포스터 문안과 게시 채널을 확정한다",
                                null),
                        registrant)
                .subWorkId();
    }

    // 검토 단계까지 정상 경로(TR-01 → TR-02)로 올려둔다. 착수·검토요청은 담당자의 몫이다
    private Long subWorkInReview(long subWorkTypeId) {
        Long subWorkId = createSubWork(subWorkTypeId);
        transition(subWorkId, TransitionAction.START, null, registrant);
        transition(subWorkId, TransitionAction.REQUEST_REVIEW, null, registrant);
        return subWorkId;
    }

    private SubWorkTransitionResponse transition(
            Long subWorkId, TransitionAction action, String reason, MemberEntity performer) {
        entityManager.flush();
        entityManager.clear();
        return subWorkService.transitionSubWork(
                subWorkId, new SubWorkTransitionRequest(action, reason), performer);
    }

    private SubWorkVoteResponse vote(Long subWorkId, VoteChoice choice, MemberEntity voter) {
        entityManager.flush();
        entityManager.clear();
        return subWorkService.voteOnSubWork(subWorkId, new SubWorkVoteRequest(choice), voter);
    }

    private void completeChecklist(Long subWorkId) {
        entityManager.flush();
        entityManager.clear();
        SubWorkEntity subWork = subWorkRepository.findById(subWorkId).orElseThrow();
        List<Long> itemIds =
                subWorkChecklistItemRepository.findBySubWorkOrderBySortOrderAsc(subWork).stream()
                        .map(SubWorkChecklistItemEntity::getId)
                        .toList();
        for (Long itemId : itemIds) {
            entityManager.flush();
            entityManager.clear();
            subWorkService.updateChecklistItem(
                    subWorkId, itemId, new SubWorkChecklistItemUpdateRequest(true), registrant);
        }
    }

    private WorkStatus detailWorkStatus(Long subWorkId) {
        entityManager.flush();
        entityManager.clear();
        return subWorkService.getSubWork(subWorkId, registrant).workStatus();
    }

    private ApprovalStatus detailApprovalStatus(Long subWorkId) {
        entityManager.flush();
        entityManager.clear();
        return subWorkService.getSubWork(subWorkId, registrant).approvalStatus();
    }
}
