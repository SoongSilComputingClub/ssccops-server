package org.sscc.ssccopsserver.domain.academicprogram.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramTransitionRequest;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramApprovalEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramApprovalPoint;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramApprovalStatus;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramStatus;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramTransition;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramApprovalRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramTypeRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.CurriculumItemRepository;
import org.sscc.ssccopsserver.domain.academicprogram.service.AcademicProgramApprovalEffectsService;
import org.sscc.ssccopsserver.domain.academicprogram.service.AcademicProgramService;
import org.sscc.ssccopsserver.domain.event.code.EventStatus;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.event.repository.EventClassificationRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventRepository;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.code.FormStatusAction;
import org.sscc.ssccopsserver.domain.form.code.QuestionItemType;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent.Page;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent.QuestionItem;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormResponseHistoryRepository;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.AuthorityRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleClassificationRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.domain.member.repository.RoleAuthorityRelationRepository;
import org.sscc.ssccopsserver.support.AcademicProgramFixture;
import org.sscc.ssccopsserver.support.AuthorityFixture;
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

/*
 * 학술 활동 국장 전용 전이 API (#133 · POST /v1/academic-programs/{id}/transitions).
 *
 * START_RECRUITMENT·APPROVE_COMPLETION·REOPEN(#597)·DISCONTINUE·REINSTATE(#611) 다섯 다
 * ACADEMIC_PROGRAM_MANAGE라 AcademicProgramTypeControllerTest와 같은 방식(관리자·비관리자
 * 토큰)으로 권한을 가른다.
 *
 * 종료·폐지가 멈추는 쓰기 경로 여덟은 AcademicProgramCompletionControllerTest가 한 표로 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
@Transactional
class AcademicProgramTransitionControllerTest {

    private static final String PROGRAMS = "/v1/academic-programs";

    @Autowired private MockMvc mockMvc;
    @Autowired private EntityManager entityManager;

    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private AuthorityRepository authorityRepository;
    @Autowired private RoleAuthorityRelationRepository roleAuthorityRelationRepository;

    @Autowired private EventRepository eventRepository;
    @Autowired private EventClassificationRepository eventClassificationRepository;
    @Autowired private AcademicProgramRepository academicProgramRepository;
    @Autowired private AcademicProgramTypeRepository academicProgramTypeRepository;
    @Autowired private CurriculumItemRepository curriculumItemRepository;
    @Autowired private FormResponseHistoryRepository formResponseHistoryRepository;
    @Autowired private AcademicProgramApprovalRepository academicProgramApprovalRepository;
    @Autowired private FormRepository formRepository;

    @Autowired private AcademicProgramApprovalEffectsService approvalEffectsService;
    @Autowired private AcademicProgramService academicProgramService;

    private UUID managerToken;
    private MemberEntity manager;
    private UUID outsiderToken;

    @BeforeEach
    void setUp() {
        managerToken = UUID.randomUUID();
        manager = saveMember(managerToken, "20260501", "학술국장");
        grant(manager, AuthorityCode.ACADEMIC_PROGRAM_MANAGE);

        // ACADEMIC_PROGRAM_MANAGE가 없는 회원 — 조회가 아니라 전이라 outsider도 인증만으로는
        // 통과하지 못한다는 것을 드러내려면 권한이 아예 없는 게 아니라 '다른 권한만' 가져야 한다
        outsiderToken = UUID.randomUUID();
        MemberEntity outsider = saveMember(outsiderToken, "20260502", "국원");
        grant(outsider, AuthorityCode.WORK_MANAGE);
    }

    // ------------------------------------------------------------------ START_RECRUITMENT

    @Test
    void startRecruitmentAdvancesToOngoingAndOpensLinkedForm() throws Exception {
        AcademicProgramEntity program = createApprovedProgramReadyToOpen("모집 시작 스터디");
        Long eventId = program.getEvent().getId();
        // 이관이 만든 event는 DRAFT다 — 모집 시작 전에는 공개 앱에 뜨지 않는다
        assertThat(eventRepository.findById(eventId).orElseThrow().getStatus())
                .isEqualTo(EventStatus.DRAFT);

        mockMvc.perform(
                        authorized(
                                        post(PROGRAMS + "/{id}/transitions", program.getId()),
                                        managerToken)
                                .content(
                                        """
                                        {"transition": "START_RECRUITMENT"}
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.academicProgramId").value(program.getId()))
                .andExpect(jsonPath("$.data.beforeSttsCd").value("APPROVED"))
                .andExpect(jsonPath("$.data.afterSttsCd").value("ONGOING"))
                .andExpect(jsonPath("$.data.formReceiptStatus").value("ACCEPTING"));

        flushAndClear();
        assertThat(academicProgramRepository.findById(program.getId()).orElseThrow().getStatus())
                .isEqualTo(AcademicProgramStatus.ONGOING);
        // 모집 시작이 연결된 event를 같은 트랜잭션에서 게시한다 (#187)
        assertThat(eventRepository.findById(eventId).orElseThrow().getStatus())
                .isEqualTo(EventStatus.PUBLISHED);
    }

    // 이미 ONGOING인 활동에서 START_RECRUITMENT을 다시 부르면 409 — event가 이중 게시되지 않는다
    @Test
    void repeatedStartRecruitmentIsRejectedAndDoesNotRepublishEvent() throws Exception {
        AcademicProgramEntity program = createOngoingProgram("재시도 스터디");
        Long eventId = program.getEvent().getId();
        assertThat(eventRepository.findById(eventId).orElseThrow().getStatus())
                .isEqualTo(EventStatus.PUBLISHED);

        mockMvc.perform(
                        authorized(
                                        post(PROGRAMS + "/{id}/transitions", program.getId()),
                                        managerToken)
                                .content(
                                        """
                                        {"transition": "START_RECRUITMENT"}
                                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_ACADEMIC_PROGRAM_TRANSITION"));

        flushAndClear();
        assertThat(eventRepository.findById(eventId).orElseThrow().getStatus())
                .isEqualTo(EventStatus.PUBLISHED);
    }

    // 승인 후속 처리가 항상 문항 0개 폼을 만들어 두므로, 채우지 않은 채 모집을 시작하면
    // 폼 도메인의 400을 그대로 전파해야 한다(감싸지 않는다)
    @Test
    void startRecruitmentWithoutQuestionsPropagatesFormDomainError() throws Exception {
        AcademicProgramEntity program = createApprovedProgramWithEmptyForm("문항 없는 스터디");

        mockMvc.perform(
                        authorized(
                                        post(PROGRAMS + "/{id}/transitions", program.getId()),
                                        managerToken)
                                .content(
                                        """
                                        {"transition": "START_RECRUITMENT"}
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FORM_HAS_NO_QUESTION"));
    }

    // 방어적 코드 — 승인 후속 처리를 거치지 않아 폼 자체가 연결되지 않은 경우
    @Test
    void startRecruitmentWithoutLinkedFormReturns409() throws Exception {
        MemberEntity leader = saveMember(UUID.randomUUID(), "20260503", "리더A");
        AcademicProgramEntity program = createApprovedProgramWithoutEffects("폼 미연결 스터디", leader);

        mockMvc.perform(
                        authorized(
                                        post(PROGRAMS + "/{id}/transitions", program.getId()),
                                        managerToken)
                                .content(
                                        """
                                        {"transition": "START_RECRUITMENT"}
                                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FORM_NOT_LINKED"));
    }

    // ------------------------------------------------------------------ APPROVE_COMPLETION

    /*
     * 종료는 접수 중인 모집 폼을 같은 트랜잭션에서 마감한다(#597) — 활동이 쓰기를 멈췄는데 폼만
     * 응답을 받으면 아무도 선발할 수 없는 지원서가 쌓인다. 폼을 바꿨으므로 응답에
     * formReceiptStatus가 실린다.
     */
    @Test
    void approveCompletionRecordsApprovalAndAdvancesToCompleted() throws Exception {
        AcademicProgramEntity program = createOngoingProgram("종료 승인 스터디");
        Long formId = program.getEvent().getForm().getId();

        mockMvc.perform(
                        authorized(
                                        post(PROGRAMS + "/{id}/transitions", program.getId()),
                                        managerToken)
                                .content(
                                        """
                                        {"transition": "APPROVE_COMPLETION"}
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.beforeSttsCd").value("ONGOING"))
                .andExpect(jsonPath("$.data.afterSttsCd").value("COMPLETED"))
                .andExpect(jsonPath("$.data.formReceiptStatus").value("CLOSED"));

        flushAndClear();
        assertThat(academicProgramRepository.findById(program.getId()).orElseThrow().getStatus())
                .isEqualTo(AcademicProgramStatus.COMPLETED);
        assertThat(formRepository.findById(formId).orElseThrow().getStatus())
                .isEqualTo(FormStatus.CLOSED);

        List<AcademicProgramApprovalEntity> approvals = academicProgramApprovalRepository.findAll();
        assertThat(approvals)
                .anySatisfy(
                        approval -> {
                            assertThat(approval.getAcademicProgram().getId())
                                    .isEqualTo(program.getId());
                            assertThat(approval.getPoint())
                                    .isEqualTo(AcademicProgramApprovalPoint.COMPLETION);
                            assertThat(approval.getStatus())
                                    .isEqualTo(AcademicProgramApprovalStatus.APPROVED);
                            assertThat(approval.getApprovedAt()).isNotNull();
                        });
    }

    // 이미 마감된 폼은 건드리지 않는다 — 어느 상태에서 닫을 수 있는지는 폼의 전이표가 답한다
    @Test
    void approveCompletionLeavesAlreadyClosedFormAlone() throws Exception {
        AcademicProgramEntity program = createOngoingProgram("이미 마감한 스터디");
        FormEntity form =
                formRepository.findById(program.getEvent().getForm().getId()).orElseThrow();
        form.changeStatus(FormStatusAction.CLOSE);
        flushAndClear();

        mockMvc.perform(
                        authorized(
                                        post(PROGRAMS + "/{id}/transitions", program.getId()),
                                        managerToken)
                                .content(
                                        """
                                        {"transition": "APPROVE_COMPLETION"}
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.afterSttsCd").value("COMPLETED"))
                .andExpect(jsonPath("$.data.formReceiptStatus").value(Matchers.nullValue()));
    }

    // ------------------------------------------------------------------ REOPEN (#597)

    /*
     * 재시작은 종료를 되돌리고 그 사실을 승인 이력에 한 줄 덧붙인다 — 종료 줄은 지우지 않는다.
     * **모집 폼은 다시 열지 않는다**(ADR-0057) — 종료가 닫은 폼은 닫힌 채이고 formReceiptStatus는
     * null이다.
     */
    @Test
    void reopenReturnsCompletedProgramToOngoingWithoutReopeningForm() throws Exception {
        AcademicProgramEntity program = createCompletedProgram("재시작 스터디");
        Long formId = program.getEvent().getForm().getId();

        mockMvc.perform(
                        authorized(
                                        post(PROGRAMS + "/{id}/transitions", program.getId()),
                                        managerToken)
                                .content(
                                        """
                                        {"transition": "REOPEN"}
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.beforeSttsCd").value("COMPLETED"))
                .andExpect(jsonPath("$.data.afterSttsCd").value("ONGOING"))
                .andExpect(jsonPath("$.data.formReceiptStatus").value(Matchers.nullValue()));

        flushAndClear();
        assertThat(academicProgramRepository.findById(program.getId()).orElseThrow().getStatus())
                .isEqualTo(AcademicProgramStatus.ONGOING);
        assertThat(formRepository.findById(formId).orElseThrow().getStatus())
                .isEqualTo(FormStatus.CLOSED);
        assertThat(pointsOf(program))
                .containsExactly(
                        AcademicProgramApprovalPoint.COMPLETION,
                        AcademicProgramApprovalPoint.REOPEN);
    }

    // 종료 → 재시작 → 종료를 반복한 사실도 기록이다 — 이력은 덧붙이기만 한다
    @Test
    void completeReopenCompleteKeepsEveryStepInHistory() throws Exception {
        AcademicProgramEntity program = createCompletedProgram("반복 스터디");

        transition(program, "REOPEN");
        transition(program, "APPROVE_COMPLETION");

        flushAndClear();
        assertThat(academicProgramRepository.findById(program.getId()).orElseThrow().getStatus())
                .isEqualTo(AcademicProgramStatus.COMPLETED);
        assertThat(pointsOf(program))
                .containsExactly(
                        AcademicProgramApprovalPoint.COMPLETION,
                        AcademicProgramApprovalPoint.REOPEN,
                        AcademicProgramApprovalPoint.COMPLETION);
        assertThat(academicProgramApprovalRepository.findAll())
                .filteredOn(
                        approval -> approval.getAcademicProgram().getId().equals(program.getId()))
                .allSatisfy(
                        approval -> {
                            assertThat(approval.getStatus())
                                    .isEqualTo(AcademicProgramApprovalStatus.APPROVED);
                            assertThat(approval.getSession()).isNull();
                            assertThat(approval.getApprover().getId()).isEqualTo(manager.getId());
                            assertThat(approval.getApprovedAt()).isNotNull();
                        });
    }

    // 진행 중인 활동은 다시 열 것이 없다 — 전이표가 COMPLETED에서만 REOPEN을 허용한다
    @Test
    void reopenFromOngoingReturns409() throws Exception {
        AcademicProgramEntity program = createOngoingProgram("진행 중 스터디");

        mockMvc.perform(
                        authorized(
                                        post(PROGRAMS + "/{id}/transitions", program.getId()),
                                        managerToken)
                                .content(
                                        """
                                        {"transition": "REOPEN"}
                                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_ACADEMIC_PROGRAM_TRANSITION"));
    }

    @Test
    void reopenWithoutManageAuthorityIsForbidden() throws Exception {
        AcademicProgramEntity program = createCompletedProgram("권한 없이 재시작");

        mockMvc.perform(
                        authorized(
                                        post(PROGRAMS + "/{id}/transitions", program.getId()),
                                        outsiderToken)
                                .content(
                                        """
                                        {"transition": "REOPEN"}
                                        """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // ------------------------------------------------------------------ DISCONTINUE · REINSTATE
    // (#611)

    /*
     * 모집 전(승인)에 폐지한 건은 **승인으로** 복원된다 — 언제나 진행 중으로 되돌리면 모집이 열린
     * 적 없는 «진행 중»이 생긴다. 되돌아갈 곳은 폐지 줄이 적어 둔 폐지 전 상태이고, 이력은 두 줄이
     * 덧붙는다(폐지 줄을 지우지 않는다). 폼은 DRAFT라 닫을 것이 없다.
     */
    @Test
    void discontinueApprovedProgramThenReinstateReturnsToApproved() throws Exception {
        AcademicProgramEntity program = createApprovedProgramReadyToOpen("모집 전 폐지 스터디");

        mockMvc.perform(discontinueRequest(program, "최소 인원 미달", managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.beforeSttsCd").value("APPROVED"))
                .andExpect(jsonPath("$.data.afterSttsCd").value("DISCONTINUED"))
                .andExpect(jsonPath("$.data.formReceiptStatus").value(Matchers.nullValue()));

        mockMvc.perform(
                        authorized(
                                        post(PROGRAMS + "/{id}/transitions", program.getId()),
                                        managerToken)
                                .content(
                                        """
                                        {"transition": "REINSTATE"}
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.beforeSttsCd").value("DISCONTINUED"))
                .andExpect(jsonPath("$.data.afterSttsCd").value("APPROVED"))
                .andExpect(jsonPath("$.data.formReceiptStatus").value(Matchers.nullValue()));

        flushAndClear();
        assertThat(academicProgramRepository.findById(program.getId()).orElseThrow().getStatus())
                .isEqualTo(AcademicProgramStatus.APPROVED);
        List<AcademicProgramApprovalEntity> approvals = approvalsOf(program);
        assertThat(approvals)
                .extracting(AcademicProgramApprovalEntity::getPoint)
                .containsExactly(
                        AcademicProgramApprovalPoint.DISCONTINUE,
                        AcademicProgramApprovalPoint.REINSTATE);
        // 폐지 줄만 폐지 전 상태를 적고, 사유는 폐지 줄에 남는다(복원 사유는 선택이라 비었다)
        assertThat(approvals.get(0).getStatusBeforeTransition())
                .isEqualTo(AcademicProgramStatus.APPROVED);
        assertThat(approvals.get(0).getOpinionContent()).isEqualTo("최소 인원 미달");
        assertThat(approvals.get(1).getStatusBeforeTransition()).isNull();
        assertThat(approvals.get(1).getOpinionContent()).isNull();
    }

    /*
     * 진행 중에 폐지하면 접수 중인 모집 폼을 같은 트랜잭션에서 마감하고(종료와 같은 자리), 복원은
     * **진행 중으로** 돌아가되 폼을 다시 열지 않는다 — 모집은 폼 화면에서 따로 연다(ADR-0058).
     * 복원 사유는 선택이지만 주면 복원 줄에 남는다.
     */
    @Test
    void discontinueOngoingProgramClosesFormAndReinstateReturnsToOngoingWithoutReopening()
            throws Exception {
        AcademicProgramEntity program = createOngoingProgram("진행 중 폐지 스터디");
        Long formId = program.getEvent().getForm().getId();

        mockMvc.perform(discontinueRequest(program, "팀원 이탈로 운영 불가", managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.beforeSttsCd").value("ONGOING"))
                .andExpect(jsonPath("$.data.afterSttsCd").value("DISCONTINUED"))
                .andExpect(jsonPath("$.data.formReceiptStatus").value("CLOSED"));
        entityManager.flush();
        assertThat(formRepository.findById(formId).orElseThrow().getStatus())
                .isEqualTo(FormStatus.CLOSED);

        mockMvc.perform(
                        authorized(
                                        post(PROGRAMS + "/{id}/transitions", program.getId()),
                                        managerToken)
                                .content(
                                        """
                                        {"transition": "REINSTATE", "reason": "추가 모집으로 인원 충원"}
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.afterSttsCd").value("ONGOING"))
                .andExpect(jsonPath("$.data.formReceiptStatus").value(Matchers.nullValue()));

        flushAndClear();
        assertThat(formRepository.findById(formId).orElseThrow().getStatus())
                .isEqualTo(FormStatus.CLOSED);
        List<AcademicProgramApprovalEntity> approvals = approvalsOf(program);
        assertThat(approvals.get(0).getStatusBeforeTransition())
                .isEqualTo(AcademicProgramStatus.ONGOING);
        assertThat(approvals.get(1).getOpinionContent()).isEqualTo("추가 모집으로 인원 충원");
    }

    // 폐지·복원을 반복하면 복원은 **마지막** 폐지 줄을 본다 — 두 번째 폐지는 진행 중에서 했다
    @Test
    void reinstateFollowsTheLatestDiscontinuation() throws Exception {
        AcademicProgramEntity program = createApprovedProgramReadyToOpen("두 번 폐지 스터디");

        mockMvc.perform(discontinueRequest(program, "첫 폐지", managerToken))
                .andExpect(status().isOk());
        transition(program, "REINSTATE");
        transition(program, "START_RECRUITMENT");
        mockMvc.perform(discontinueRequest(program, "두 번째 폐지", managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.beforeSttsCd").value("ONGOING"));
        transition(program, "REINSTATE");

        flushAndClear();
        assertThat(academicProgramRepository.findById(program.getId()).orElseThrow().getStatus())
                .isEqualTo(AcademicProgramStatus.ONGOING);
    }

    // 끝난 것은 중단된 것이 아니다 — 종료에서는 폐지할 수 없다(ADR-0058)
    @Test
    void discontinueFromCompletedReturns409() throws Exception {
        AcademicProgramEntity program = createCompletedProgram("종료된 스터디");

        mockMvc.perform(discontinueRequest(program, "사유", managerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_ACADEMIC_PROGRAM_TRANSITION"));
    }

    /*
     * 폐지는 사유가 필수다 — 공백만 있는 사유도 여기 걸린다. 전이 가능 여부보다 뒤라, 이 활동은
     * 폐지할 수 있는 상태(진행 중)여야 400이 보인다.
     */
    @Test
    void discontinueWithoutReasonReturns400() throws Exception {
        AcademicProgramEntity program = createOngoingProgram("사유 없는 폐지");

        mockMvc.perform(discontinueRequest(program, "   ", managerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DISCONTINUATION_REASON_REQUIRED"));
    }

    // 폐지되지 않은 활동은 복원할 것이 없다
    @Test
    void reinstateFromOngoingReturns409() throws Exception {
        AcademicProgramEntity program = createOngoingProgram("폐지 안 된 스터디");

        mockMvc.perform(
                        authorized(
                                        post(PROGRAMS + "/{id}/transitions", program.getId()),
                                        managerToken)
                                .content(
                                        """
                                        {"transition": "REINSTATE"}
                                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_ACADEMIC_PROGRAM_TRANSITION"));
    }

    @Test
    void discontinueWithoutManageAuthorityIsForbidden() throws Exception {
        AcademicProgramEntity program = createOngoingProgram("권한 없이 폐지");

        mockMvc.perform(discontinueRequest(program, "사유", outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    /*
     * 폐지된 활동은 www 공개 목록·상세에서 사라진다 — **새 조건이 아니라** 폐지가 모집 폼을
     * 마감한 결과다(학술 행사는 폼이 접수 중일 때만 공개 · #187 · ADR-0058). 복원해도 모집을 다시
     * 열기 전까지는 보이지 않는다. 상세의 404는 실패 요청이라 마지막에 부른다.
     */
    @Test
    void discontinuedProgramDisappearsFromPublicEventsAndStaysHiddenAfterReinstate()
            throws Exception {
        AcademicProgramEntity program = createOngoingProgram("공개 중 폐지 스터디");
        Long eventId = program.getEvent().getId();

        mockMvc.perform(get("/public/v1/events").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].eventId", Matchers.hasItem(eventId.intValue())));

        mockMvc.perform(discontinueRequest(program, "최소 인원 미달", managerToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/public/v1/events").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath(
                                "$.data[*].eventId",
                                Matchers.not(Matchers.hasItem(eventId.intValue()))));

        transition(program, "REINSTATE");

        mockMvc.perform(get("/public/v1/events").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath(
                                "$.data[*].eventId",
                                Matchers.not(Matchers.hasItem(eventId.intValue()))));
        mockMvc.perform(get("/public/v1/events/{eventId}", eventId))
                .andExpect(status().isNotFound());
    }

    // 목록의 sttsCd 필터가 폐지를 받는다 — 대시보드의 «폐지» 칸이 이 필터를 쓴다
    @Test
    void listFiltersDiscontinuedPrograms() throws Exception {
        AcademicProgramEntity discontinued = createOngoingProgram("폐지된 스터디");
        createOngoingProgram("살아 있는 스터디");
        mockMvc.perform(discontinueRequest(discontinued, "최소 인원 미달", managerToken))
                .andExpect(status().isOk());

        mockMvc.perform(
                        authorized(get(PROGRAMS), managerToken)
                                .param("sttsCd", "DISCONTINUED")
                                .param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data[0].academicProgramId").value(discontinued.getId()))
                .andExpect(jsonPath("$.data[0].sttsCd").value("DISCONTINUED"))
                // 지연은 진행 중만이라 폐지는 들지 않는다(#610)
                .andExpect(jsonPath("$.data[0].isDelayed").value(false));
    }

    // 승인 이력 API(#139)가 두 줄을 사유와 함께 보여 준다 — 화면의 «처리 이력»이 이것을 그린다
    @Test
    void approvalHistoryShowsDiscontinueAndReinstateRows() throws Exception {
        AcademicProgramEntity program = createOngoingProgram("이력 확인 스터디");
        mockMvc.perform(discontinueRequest(program, "최소 인원 미달", managerToken))
                .andExpect(status().isOk());
        transition(program, "REINSTATE");

        mockMvc.perform(
                        authorized(
                                get(PROGRAMS + "/{id}/approvals", program.getId()), managerToken))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath(
                                "$.data[*].aprvSeCd",
                                Matchers.containsInAnyOrder("DISCONTINUE", "REINSTATE")))
                .andExpect(
                        jsonPath(
                                "$.data[?(@.aprvSeCd == 'DISCONTINUE')].opnnCn",
                                Matchers.contains("최소 인원 미달")));
    }

    // ------------------------------------------------------------------ 공통 거절

    // APPROVED 상태에서 APPROVE_COMPLETION은 정의되지 않은 전이다(ONGOING에서만 허용)
    @Test
    void undefinedTransitionReturns409() throws Exception {
        MemberEntity leader = saveMember(UUID.randomUUID(), "20260504", "리더B");
        AcademicProgramEntity program = createApprovedProgramWithoutEffects("전이표 위반 스터디", leader);

        mockMvc.perform(
                        authorized(
                                        post(PROGRAMS + "/{id}/transitions", program.getId()),
                                        managerToken)
                                .content(
                                        """
                                        {"transition": "APPROVE_COMPLETION"}
                                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_ACADEMIC_PROGRAM_TRANSITION"));
    }

    @Test
    void transitionWithoutManageAuthorityIsForbidden() throws Exception {
        MemberEntity leader = saveMember(UUID.randomUUID(), "20260505", "리더C");
        AcademicProgramEntity program = createApprovedProgramWithoutEffects("권한 없음 스터디", leader);

        mockMvc.perform(
                        authorized(
                                        post(PROGRAMS + "/{id}/transitions", program.getId()),
                                        outsiderToken)
                                .content(
                                        """
                                        {"transition": "START_RECRUITMENT"}
                                        """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void transitionOnUnknownAcademicProgramReturns404() throws Exception {
        mockMvc.perform(
                        authorized(post(PROGRAMS + "/{id}/transitions", 999_999L), managerToken)
                                .content(
                                        """
                                        {"transition": "START_RECRUITMENT"}
                                        """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACADEMIC_PROGRAM_NOT_FOUND"));
    }

    // ------------------------------------------------------------------ 헬퍼

    private int studentNumberSeq = 0;

    // 승인 후속 처리(효과 서비스)까지 거쳐 문항 1개를 채운, 곧바로 모집을 시작할 수 있는 상태
    private AcademicProgramEntity createApprovedProgramReadyToOpen(String title) {
        AcademicProgramEntity program = createApprovedProgramWithEmptyForm(title);
        addQuestionItem(program);
        flushAndClear();
        return academicProgramRepository.findById(program.getId()).orElseThrow();
    }

    // 승인 후속 처리는 거쳤지만 문항을 채우지 않아 여전히 빈 폼인 상태
    private AcademicProgramEntity createApprovedProgramWithEmptyForm(String title) {
        MemberEntity leader = saveMember(UUID.randomUUID(), nextStudentNumber(), title + " 리더");
        AcademicProgramEntity program = createAcademicProgramFixture("STUDY", title, leader);
        approvalEffectsService.applyPostApprovalEffects(program);
        flushAndClear();
        return academicProgramRepository.findById(program.getId()).orElseThrow();
    }

    // 승인 후속 처리 자체를 거치지 않아 폼이 아예 연결되지 않은 상태 (#150 없이 픽스처만 쓴 경우)
    private AcademicProgramEntity createApprovedProgramWithoutEffects(
            String title, MemberEntity leader) {
        AcademicProgramEntity program = createAcademicProgramFixture("STUDY", title, leader);
        flushAndClear();
        return academicProgramRepository.findById(program.getId()).orElseThrow();
    }

    private AcademicProgramEntity createOngoingProgram(String title) {
        AcademicProgramEntity program = createApprovedProgramReadyToOpen(title);
        academicProgramService.transition(
                program.getId(),
                new AcademicProgramTransitionRequest(
                        AcademicProgramTransition.START_RECRUITMENT, null, null, null),
                manager);
        flushAndClear();
        return academicProgramRepository.findById(program.getId()).orElseThrow();
    }

    private AcademicProgramEntity createCompletedProgram(String title) {
        AcademicProgramEntity program = createOngoingProgram(title);
        transition(program, "APPROVE_COMPLETION");
        flushAndClear();
        return academicProgramRepository.findById(program.getId()).orElseThrow();
    }

    private void transition(AcademicProgramEntity program, String transition) {
        academicProgramService.transition(
                program.getId(),
                new AcademicProgramTransitionRequest(
                        AcademicProgramTransition.valueOf(transition), null, null, null),
                manager);
        entityManager.flush();
    }

    private MockHttpServletRequestBuilder discontinueRequest(
            AcademicProgramEntity program, String reason, UUID token) {
        return authorized(post(PROGRAMS + "/{id}/transitions", program.getId()), token)
                .content(
                        """
                        {"transition": "DISCONTINUE", "reason": "%s"}
                        """
                                .formatted(reason));
    }

    // 이 활동의 승인 이력을 기록된 순서(식별자 오름차순)대로
    private List<AcademicProgramApprovalEntity> approvalsOf(AcademicProgramEntity program) {
        return academicProgramApprovalRepository.findAll().stream()
                .filter(approval -> approval.getAcademicProgram().getId().equals(program.getId()))
                .sorted(Comparator.comparing(AcademicProgramApprovalEntity::getId))
                .toList();
    }

    // 이 활동의 승인 이력 지점을 기록된 순서(식별자 오름차순)대로
    private List<AcademicProgramApprovalPoint> pointsOf(AcademicProgramEntity program) {
        return academicProgramApprovalRepository.findAll().stream()
                .filter(approval -> approval.getAcademicProgram().getId().equals(program.getId()))
                .sorted(Comparator.comparing(AcademicProgramApprovalEntity::getId))
                .map(AcademicProgramApprovalEntity::getPoint)
                .toList();
    }

    private AcademicProgramEntity createAcademicProgramFixture(
            String typeCd, String title, MemberEntity leaderAndProposer) {
        return AcademicProgramFixture.save(
                eventRepository,
                eventClassificationRepository,
                academicProgramRepository,
                academicProgramTypeRepository,
                curriculumItemRepository,
                formRepository,
                formResponseHistoryRepository,
                typeCd,
                title,
                leaderAndProposer,
                List.of("1주차"));
    }

    private String nextStudentNumber() {
        return "2026090" + (studentNumberSeq++);
    }

    private void addQuestionItem(AcademicProgramEntity academicProgram) {
        EventEntity event =
                eventRepository.findById(academicProgram.getEvent().getId()).orElseThrow();
        FormEntity form = formRepository.findById(event.getForm().getId()).orElseThrow();
        form.update(
                form.getTitle(),
                new QuestionCompositionContent(
                        List.of(new Page("페이지1", null)),
                        List.of(
                                new QuestionItem(
                                        "q1",
                                        "질문1",
                                        QuestionItemType.SHORT_TEXT,
                                        false,
                                        0,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null))),
                form.getReceiptBeginAt(),
                form.getReceiptEndAt(),
                form.isMultipleResponseAllowed());
    }

    private MemberEntity saveMember(UUID authUserId, String studentNumber, String name) {
        return MemberFixture.save(
                memberRepository,
                memberGradeRepository,
                memberStatusRepository,
                authUserId,
                studentNumber,
                name,
                studentNumber + "@sscc.org");
    }

    private void grant(MemberEntity member, AuthorityCode authority) {
        AuthorityFixture.grant(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                authorityRepository,
                roleAuthorityRelationRepository,
                member,
                authority);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private static MockHttpServletRequestBuilder authorized(
            MockHttpServletRequestBuilder builder, UUID authUserId) {
        return builder.header("Authorization", "Bearer " + authUserId)
                .contentType(MediaType.APPLICATION_JSON);
    }
}
