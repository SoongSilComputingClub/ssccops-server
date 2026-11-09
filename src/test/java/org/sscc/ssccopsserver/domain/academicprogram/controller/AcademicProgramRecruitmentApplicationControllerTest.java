package org.sscc.ssccopsserver.domain.academicprogram.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramTypeRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.CurriculumItemRepository;
import org.sscc.ssccopsserver.domain.event.code.EventParticipantStatus;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.event.entity.EventParticipantEntity;
import org.sscc.ssccopsserver.domain.event.repository.EventClassificationRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventParticipantRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventRepository;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.code.QuestionItemType;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent.Page;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent.QuestionItem;
import org.sscc.ssccopsserver.domain.form.entity.ResponseContent;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormResponseHistoryRepository;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.code.MemberGradeCode;
import org.sscc.ssccopsserver.domain.member.code.MemberStatusCode;
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
import org.sscc.ssccopsserver.support.MemberRoleFixture;
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

/*
 * 리더가 읽는 지원서 상세 (ssccops#625 · GET /v1/academic-programs/{id}/recruitment/applications/{formRspnsId}).
 *
 * 자격은 **리더 한 사람**이다 — 학술국장(MANAGE) · 국원(시드된 그 역할 · READ) · 팀원 · 다른 프로그램의
 * 스터디장 · 다른 권한만 가진 회원 모두 403이다. 목록(리더 또는 READ)과 갈리는 자리라 국원 403을 따로 본다.
 *
 * **리더는 시드된 «스터디장» 역할을 실제로 받은 회원이다**(MemberRoleFixture.STUDY_LEADER). 판정은
 * leadr_mbr_id라 역할이 없어도 통과하지만, 역할 없는 맨 회원으로만 보면 «리더 역할에 무엇이 붙었는가»를
 * 아무도 두드리지 않는다(ADR-0067 사건 · ADR-0068 «시험»). 국원도 같은 이유로 시드된 역할을 붙인다.
 *
 * 연락처는 **필드 자체가 없어야** 한다. 리더가 MEMBER_MANAGE를 겸하면 폼 쪽 중간 값에는 연락처가 실리므로,
 * 그 경우에 운영자 경로로는 연락처가 보이는데 이 경로로는 없다는 것을 한 시험에서 함께 본다 — 연락처가
 * 비어 있는 지원자로만 보면 «없는 값이라 없다»와 «막혀서 없다»가 구별되지 않는다.
 *
 * 모집 시작은 #133의 전이 API를 그대로 태운다(AcademicProgramRecruitmentControllerTest와 같은 이유 —
 * 상태만 심으면 폼이 DRAFT로 남는다). 실패를 기대하는 요청은 테스트마다 마지막에 한 번만 부른다
 * (domain/academicprogram/AGENTS.md). 애노테이션 구성이 다른 학술 컨트롤러 테스트와 같아 스프링 컨텍스트를
 * 함께 쓴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
@Transactional
class AcademicProgramRecruitmentApplicationControllerTest {

    private static final String PROGRAMS = "/v1/academic-programs";

    /* 지원자의 연락처 — 실제 번호가 아닌 시험 값이다 */
    private static final String APPLICANT_TELNO = "010-0000-0625";

    @Autowired private MockMvc mockMvc;

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
    @Autowired private EventParticipantRepository eventParticipantRepository;
    @Autowired private FormRepository formRepository;
    @Autowired private FormResponseHistoryRepository formResponseHistoryRepository;
    @Autowired private AcademicProgramRepository academicProgramRepository;
    @Autowired private AcademicProgramTypeRepository academicProgramTypeRepository;
    @Autowired private CurriculumItemRepository curriculumItemRepository;

    private UUID managerToken;
    private MemberEntity manager;
    private UUID leaderToken;
    private MemberEntity leader;
    private UUID staffToken;
    private UUID outsiderToken;
    private UUID otherLeaderToken;
    private MemberEntity otherLeader;

    private AcademicProgramEntity recruiting;
    private FormEntity recruitmentForm;
    private MemberEntity applicant;
    private MemberEntity anotherApplicant;

    @BeforeEach
    void setUp() throws Exception {
        managerToken = UUID.randomUUID();
        manager = saveMember(managerToken, "20261101", "학술국장");
        grant(manager, AuthorityCode.ACADEMIC_PROGRAM_MANAGE);

        leaderToken = UUID.randomUUID();
        leader = saveMember(leaderToken, "20261102", "스터디장");
        assignRole(leader, MemberRoleFixture.STUDY_LEADER);

        staffToken = UUID.randomUUID();
        assignRole(saveMember(staffToken, "20261103", "국원"), MemberRoleFixture.STAFF);

        // 권한이 아예 없는 것이 아니라 '다른 권한만' 가져야 인증만으로는 통과하지 못한다는 것이 드러난다
        outsiderToken = UUID.randomUUID();
        grant(saveMember(outsiderToken, "20261104", "다른 운영진"), AuthorityCode.WORK_MANAGE);

        // 다른 프로그램의 스터디장 — 스터디를 하나 맡았다는 사실이 남의 지원서를 여는 열쇠가 되지 않는다
        otherLeaderToken = UUID.randomUUID();
        otherLeader = saveMember(otherLeaderToken, "20261105", "다른 스터디장");
        assignRole(otherLeader, MemberRoleFixture.STUDY_LEADER);

        applicant = saveApplicantWithContact("20261106", "지원자1");
        anotherApplicant = saveMember(UUID.randomUUID(), "20261107", "지원자2");

        recruiting = createProgram("알고리즘 스터디", leader);
        recruitmentForm = linkForm(recruiting, "알고리즘 스터디");
        startRecruitment(recruiting);
    }

    // ------------------------------------------------------------------ 리더가 읽는다

    /*
     * 답과 문항 구성이 함께 오고, 연락처 · 검토 이력 · 제출 회차 · 승인 미리보기는 키 자체가 없다
     * (RecruitmentApplicationDetailResponse 주석 · 사용자 결정). 미선발이면 참가 상태 둘 다 null이다.
     */
    @Test
    void leaderReadsAnswersWithQuestionsButNoContactOrReviewHistory() throws Exception {
        Long responseId = saveResponse(applicant, "그래프를 공부하고 싶습니다");

        mockMvc.perform(authorized(get(applicationPath(recruiting, responseId)), leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.formRspnsId").value(responseId))
                .andExpect(jsonPath("$.data.rspnsSeq").value(1))
                .andExpect(jsonPath("$.data.rspnsSttsCd").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.sbmsnDt").isNotEmpty())
                .andExpect(jsonPath("$.data.member.mbrId").value(applicant.getId()))
                .andExpect(jsonPath("$.data.member.mbrNm").value("지원자1"))
                .andExpect(jsonPath("$.data.member.stdntNo").value("20261106"))
                .andExpect(jsonPath("$.data.member.mbrGrdCd").isNotEmpty())
                .andExpect(jsonPath("$.data.member.mbrSttsCd").value("ENROLLED"))
                .andExpect(jsonPath("$.data.member.telno").doesNotExist())
                .andExpect(jsonPath("$.data.qitemCpstCn.qitems[0].qitemId").value("q1"))
                .andExpect(jsonPath("$.data.qitemCpstCn.qitems[0].qitemLblNm").value("지원 동기"))
                .andExpect(jsonPath("$.data.rspnsCn.q1").value("그래프를 공부하고 싶습니다"))
                .andExpect(jsonPath("$.data.eventPtcpId").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.ptcpSttsCd").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.reviewHistories").doesNotExist())
                .andExpect(jsonPath("$.data.sbmsnSeq").doesNotExist())
                .andExpect(jsonPath("$.data.academicProgramPreview").doesNotExist());
    }

    /*
     * 이웃은 신청자 목록 기본 조회와 같은 순서다 — 제출 일시가 같으면 식별자 내림차순이라 나중에 낸
     * 지원자2가 앞이다(AcademicProgramRecruitmentControllerTest.getApplicationsCarryParticipantStatus).
     */
    @Test
    void leaderMovesBetweenApplicationsInListOrder() throws Exception {
        Long first = saveResponse(applicant, "먼저 냈습니다");
        Long second = saveResponse(anotherApplicant, "나중에 냈습니다");

        mockMvc.perform(authorized(get(applicationPath(recruiting, second)), leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.prevFormRspnsId").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.nextFormRspnsId").value(first));

        mockMvc.perform(authorized(get(applicationPath(recruiting, first)), leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.prevFormRspnsId").value(second))
                .andExpect(jsonPath("$.data.nextFormRspnsId").value(Matchers.nullValue()));
    }

    // 선발 상태는 목록과 같은 «회원 기준» 값이다 — 학술국장이 대기로 뽑으면 리더에게도 대기로 보인다
    @Test
    void leaderSeesSelectionStatus() throws Exception {
        Long responseId = saveResponse(applicant, "지원 동기입니다");
        mockMvc.perform(
                        authorized(
                                        post(
                                                PROGRAMS
                                                        + "/"
                                                        + recruiting.getId()
                                                        + "/recruitment/select"),
                                        managerToken)
                                .content(
                                        """
                                        {"selections": [{"formRspnsId": %d, "ptcpSttsCd": "WAITLISTED"}]}
                                        """
                                                .formatted(responseId)))
                .andExpect(status().isOk());
        EventParticipantEntity waitlisted =
                eventParticipantRepository
                        .findAllByEventAndStatusInOrderByIdAsc(
                                recruiting.getEvent(), List.of(EventParticipantStatus.WAITLISTED))
                        .get(0);

        mockMvc.perform(authorized(get(applicationPath(recruiting, responseId)), leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rspnsSttsCd").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.eventPtcpId").value(waitlisted.getId()))
                .andExpect(jsonPath("$.data.ptcpSttsCd").value("WAITLISTED"));
    }

    /*
     * 리더가 회원 관리 권한을 겸해도(회장이 스터디장을 맡는 경우) 이 경로로는 연락처가 나가지 않는다.
     * 같은 사람이 운영자 경로(RESPONSE_REVIEW)로 읽으면 연락처가 보인다 — 값이 있는 지원자라는 것을 거기서
     * 확인해야 «막혀서 없다»가 성립한다.
     */
    @Test
    void leaderWithMemberManageStillGetsNoContact() throws Exception {
        grant(leader, AuthorityCode.MEMBER_MANAGE);
        grant(leader, AuthorityCode.RESPONSE_REVIEW);
        Long responseId = saveResponse(applicant, "지원 동기입니다");

        mockMvc.perform(
                        authorized(
                                get(
                                        "/v1/forms/%d/responses/%d"
                                                .formatted(recruitmentForm.getId(), responseId)),
                                leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.member.telno").value(APPLICANT_TELNO));

        mockMvc.perform(authorized(get(applicationPath(recruiting, responseId)), leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.member.mbrNm").value("지원자1"))
                .andExpect(jsonPath("$.data.member.telno").doesNotExist());
    }

    // 종료는 쓰기만 멈춘다(ADR-0057) — 수료한 프로그램의 지원서도 읽힌다
    @Test
    void leaderReadsApplicationAfterCompletion() throws Exception {
        Long responseId = saveResponse(applicant, "지원 동기입니다");
        transition(recruiting, "{\"transition\": \"APPROVE_COMPLETION\"}");

        mockMvc.perform(authorized(get(applicationPath(recruiting, responseId)), leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rspnsCn.q1").value("지원 동기입니다"));
    }

    // 폐지도 같다(ADR-0058) — 진행 중에 폐지된 프로그램의 신청 명부가 사라지지 않는다
    @Test
    void leaderReadsApplicationAfterDiscontinuation() throws Exception {
        Long responseId = saveResponse(applicant, "지원 동기입니다");
        transition(recruiting, "{\"transition\": \"DISCONTINUE\", \"reason\": \"최소 인원 미달\"}");

        mockMvc.perform(authorized(get(applicationPath(recruiting, responseId)), leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rspnsCn.q1").value("지원 동기입니다"));
    }

    // ------------------------------------------------------------------ 리더가 아니면 403

    /*
     * 국원(시드된 역할 · ACADEMIC_PROGRAM_READ)은 신청자 **목록**은 보지만 지원서 **답**은 못 본다 —
     * ADR-0068은 READ에 기획안 응답만 열었다. 목록 200을 먼저 보여 둬야 이 403이 «국원이라서»가 아니라
     * «이 경로가 리더 전용이라서»라는 것이 드러난다.
     */
    @Test
    void staffReadsListButNotApplicationDetail() throws Exception {
        Long responseId = saveResponse(applicant, "지원 동기입니다");

        mockMvc.perform(
                        authorized(
                                get(
                                        PROGRAMS
                                                + "/"
                                                + recruiting.getId()
                                                + "/recruitment/applications"),
                                staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)));

        mockMvc.perform(authorized(get(applicationPath(recruiting, responseId)), staffToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // 학술국장도 이 경로로는 읽지 않는다 — 어드민 응답 상세(RESPONSE_REVIEW)를 쓴다
    @Test
    void managerReturns403() throws Exception {
        Long responseId = saveResponse(applicant, "지원 동기입니다");

        mockMvc.perform(authorized(get(applicationPath(recruiting, responseId)), managerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // 팀원(관계자)도 남의 지원서는 못 본다 — 관계자 조회(requireRelated)가 아니다
    @Test
    void teamMemberReturns403() throws Exception {
        UUID teamMemberToken = UUID.randomUUID();
        MemberEntity teamMember = saveMember(teamMemberToken, "20261108", "팀원");
        eventParticipantRepository.saveAndFlush(
                EventParticipantEntity.register(
                        recruiting.getEvent(),
                        teamMember,
                        EventParticipantStatus.CONFIRMED,
                        null,
                        manager));
        Long responseId = saveResponse(applicant, "지원 동기입니다");

        mockMvc.perform(authorized(get(applicationPath(recruiting, responseId)), teamMemberToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void otherProgramLeaderReturns403() throws Exception {
        createProgram("남의 스터디", otherLeader);
        Long responseId = saveResponse(applicant, "지원 동기입니다");

        mockMvc.perform(authorized(get(applicationPath(recruiting, responseId)), otherLeaderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void outsiderReturns403() throws Exception {
        Long responseId = saveResponse(applicant, "지원 동기입니다");

        mockMvc.perform(authorized(get(applicationPath(recruiting, responseId)), outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void withoutTokenReturns401() throws Exception {
        Long responseId = saveResponse(applicant, "지원 동기입니다");

        mockMvc.perform(get(applicationPath(recruiting, responseId)))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ 404 · 409

    /*
     * 다른 폼의 응답 번호는 없는 응답과 같은 404다 — 경로의 활동에서 폼을 파생하므로 남의 프로그램에 들어온
     * 지원서를 번호만 바꿔 읽을 수 없다.
     */
    @Test
    void foreignResponseReturns404() throws Exception {
        AcademicProgramEntity another = createProgram("남의 스터디", otherLeader);
        FormEntity anotherForm = linkForm(another, "남의 스터디");
        Long foreignResponse = saveResponse(anotherForm, applicant, "남의 스터디에 낸 지원서");

        mockMvc.perform(authorized(get(applicationPath(recruiting, foreignResponse)), leaderToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FORM_RESPONSE_NOT_FOUND"));
    }

    // 작성 중 답안은 지원이 아니다 — 목록 기본값에서도 빠지는 응답이라 상세도 없는 응답과 같다
    @Test
    void draftReturns404() throws Exception {
        Long draftId =
                formResponseHistoryRepository
                        .saveAndFlush(
                                FormResponseHistoryEntity.createDraft(
                                        recruitmentForm,
                                        anotherApplicant,
                                        ResponseContent.of(Map.of("q1", "작성 중"))))
                        .getId();

        mockMvc.perform(authorized(get(applicationPath(recruiting, draftId)), leaderToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FORM_RESPONSE_NOT_FOUND"));
    }

    @Test
    void unknownResponseReturns404() throws Exception {
        mockMvc.perform(authorized(get(applicationPath(recruiting, 999_999L)), leaderToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FORM_RESPONSE_NOT_FOUND"));
    }

    // 모집 전에는 목록과 같은 409다 — 신청을 받는 창구가 아직 열리지 않았다
    @Test
    void beforeRecruitmentReturns409() throws Exception {
        AcademicProgramEntity approved = createProgram("아직 모집 전 스터디", leader);
        linkForm(approved, "아직 모집 전 스터디");

        mockMvc.perform(authorized(get(applicationPath(approved, 1L)), leaderToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECRUITMENT_NOT_STARTED"));
    }

    // 자격을 상태보다 먼저 본다 — 남의 프로그램이 모집을 시작했는지 번호만으로 알아낼 수 없다
    @Test
    void outsiderBeforeRecruitmentGets403Not409() throws Exception {
        AcademicProgramEntity approved = createProgram("아직 모집 전 스터디", leader);
        linkForm(approved, "아직 모집 전 스터디");

        mockMvc.perform(authorized(get(applicationPath(approved, 1L)), otherLeaderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void unknownProgramReturns404() throws Exception {
        mockMvc.perform(
                        authorized(
                                get(PROGRAMS + "/999999/recruitment/applications/1"), leaderToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACADEMIC_PROGRAM_NOT_FOUND"));
    }

    // ------------------------------------------------------------------ 헬퍼

    private String applicationPath(AcademicProgramEntity program, Long formResponseId) {
        return PROGRAMS + "/" + program.getId() + "/recruitment/applications/" + formResponseId;
    }

    private AcademicProgramEntity createProgram(String title, MemberEntity owner) {
        return AcademicProgramFixture.save(
                eventRepository,
                eventClassificationRepository,
                academicProgramRepository,
                academicProgramTypeRepository,
                curriculumItemRepository,
                formRepository,
                formResponseHistoryRepository,
                "STUDY",
                title,
                owner,
                List.of("OT"));
    }

    /* 승인 후속 처리(#133)가 만드는 빈 모집 폼을 흉내 낸다 — 문항 하나, 상태 DRAFT */
    private FormEntity linkForm(AcademicProgramEntity program, String title) {
        QuestionCompositionContent composition =
                new QuestionCompositionContent(
                        List.of(new Page("기본 정보", null)),
                        List.of(
                                new QuestionItem(
                                        "q1",
                                        "지원 동기",
                                        QuestionItemType.LONG_TEXT,
                                        true,
                                        0,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null)));
        FormEntity form =
                formRepository.saveAndFlush(
                        FormEntity.create(
                                program.getLeader(),
                                title + " 모집 폼",
                                composition,
                                null,
                                null,
                                FormStatus.DRAFT));

        EventEntity event = program.getEvent();
        event.linkForm(form);
        eventRepository.saveAndFlush(event);
        return form;
    }

    /* #133의 전이 API를 그대로 태운다. 기간 없이 시작하면 곧바로 접수 중이다 */
    private void startRecruitment(AcademicProgramEntity program) throws Exception {
        transition(program, "{\"transition\": \"START_RECRUITMENT\"}");
    }

    private void transition(AcademicProgramEntity program, String body) throws Exception {
        mockMvc.perform(
                        authorized(
                                        post(PROGRAMS + "/" + program.getId() + "/transitions"),
                                        managerToken)
                                .content(body))
                .andExpect(status().isOk());
    }

    private Long saveResponse(MemberEntity respondent, String answer) {
        return saveResponse(recruitmentForm, respondent, answer);
    }

    private Long saveResponse(FormEntity form, MemberEntity respondent, String answer) {
        return formResponseHistoryRepository
                .saveAndFlush(
                        FormResponseHistoryEntity.createSubmitted(
                                form,
                                respondent,
                                ResponseContent.of(Map.of("q1", answer)),
                                Instant.parse("2026-11-01T03:00:00Z")))
                .getId();
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

    /* MemberFixture는 연락처를 비워 둔다 — 연락처가 «막혀서 없다»를 보려면 값이 있는 지원자가 필요하다 */
    private MemberEntity saveApplicantWithContact(String studentNumber, String name) {
        return memberRepository.save(
                MemberEntity.create(
                        studentNumber,
                        0,
                        name,
                        null,
                        null,
                        APPLICANT_TELNO,
                        studentNumber + "@sscc.org",
                        memberGradeRepository.findById(MemberGradeCode.TEMP.code()).orElseThrow(),
                        memberStatusRepository
                                .findById(MemberStatusCode.ENROLLED.code())
                                .orElseThrow(),
                        LocalDate.now(),
                        null,
                        null));
    }

    private void assignRole(MemberEntity member, String roleName) {
        MemberRoleFixture.assign(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                member,
                roleName);
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

    private static MockHttpServletRequestBuilder authorized(
            MockHttpServletRequestBuilder builder, UUID authUserId) {
        return builder.header("Authorization", "Bearer " + authUserId)
                .contentType(MediaType.APPLICATION_JSON);
    }
}
