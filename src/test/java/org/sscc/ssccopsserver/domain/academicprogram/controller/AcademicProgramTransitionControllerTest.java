package org.sscc.ssccopsserver.domain.academicprogram.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * START_RECRUITMENT·APPROVE_COMPLETION 둘 다 ACADEMIC_PROGRAM_MANAGE라 AcademicProgramType
 * ControllerTest와 같은 방식(관리자·비관리자 토큰)으로 권한을 가른다.
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

    @Test
    void approveCompletionRecordsApprovalAndAdvancesToCompleted() throws Exception {
        AcademicProgramEntity program = createOngoingProgram("종료 승인 스터디");

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
                .andExpect(jsonPath("$.data.formReceiptStatus").value(Matchers.nullValue()));

        flushAndClear();
        assertThat(academicProgramRepository.findById(program.getId()).orElseThrow().getStatus())
                .isEqualTo(AcademicProgramStatus.COMPLETED);

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
                        AcademicProgramTransition.START_RECRUITMENT, null, null),
                manager);
        flushAndClear();
        return academicProgramRepository.findById(program.getId()).orElseThrow();
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
