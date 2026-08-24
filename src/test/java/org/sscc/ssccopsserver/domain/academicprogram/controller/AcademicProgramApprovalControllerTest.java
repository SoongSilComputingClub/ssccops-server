package org.sscc.ssccopsserver.domain.academicprogram.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.CurriculumItemEntity;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramTypeRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.CurriculumItemRepository;
import org.sscc.ssccopsserver.domain.academicprogram.service.AcademicProgramApprovalEffectsService;
import org.sscc.ssccopsserver.domain.event.code.EventParticipantStatus;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.event.entity.EventParticipantEntity;
import org.sscc.ssccopsserver.domain.event.repository.EventClassificationRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventParticipantRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventRepository;
import org.sscc.ssccopsserver.domain.form.code.QuestionItemType;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent.Page;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent.QuestionItem;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
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

import com.jayway.jsonpath.JsonPath;

/*
 * 승인 이력 조회 API (#139 · GET /v1/academic-programs/{id}/approvals).
 *
 * 이 이슈의 핵심은 **열람 범위**라 토큰을 넷 둔다 — 학술국장(관리권한), 스터디장(소유권),
 * 팀원(그 활동의 확정 참가자이지만 리더가 아닌 회원), 그리고 다른 권한만 가진 국원. 팀원이
 * 403이라는 것이 특히 중요한데, 이 응답에만 수정요청 사유(opnnCn)가 실리기 때문이다 —
 * 상태값만 보는 화면(#134의 sessionSttsCd)은 여전히 전원에게 열려 있다.
 *
 * 이력 행은 리포지토리로 심지 않고 #136(회차 승인·수정요청)·#133(종료 승인) API를 그대로 태워
 * 만든다. 심으면 이 조회가 실제 쓰기 경로가 남기지 않는 모양의 행까지 읽게 되어, 조회가
 * 초록인데 화면은 비어 있는 상태를 잡지 못한다.
 *
 * 실패를 기대하는 요청은 테스트마다 마지막에 한 번만 부른다 — 서비스가 @Transactional이라
 * 예외가 테스트 트랜잭션을 rollback-only로 표시하고, 그 뒤 이어지는 요청은
 * UnexpectedRollbackException을 만난다(AGENTS.md).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AcademicProgramApprovalControllerTest.StubJwtDecoderConfig.class)
@Transactional
class AcademicProgramApprovalControllerTest {

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
    @Autowired private EventParticipantRepository eventParticipantRepository;
    @Autowired private FormRepository formRepository;
    @Autowired private AcademicProgramRepository academicProgramRepository;
    @Autowired private AcademicProgramTypeRepository academicProgramTypeRepository;
    @Autowired private CurriculumItemRepository curriculumItemRepository;
    @Autowired private AcademicProgramApprovalEffectsService approvalEffectsService;

    private UUID managerToken;
    private MemberEntity manager;
    private UUID leaderToken;
    private MemberEntity leader;
    private UUID teamMemberToken;
    private UUID outsiderToken;

    private AcademicProgramEntity study;

    @BeforeEach
    void setUp() {
        managerToken = UUID.randomUUID();
        manager = saveMember(managerToken, "20260801", "학술국장");
        grant(manager, AuthorityCode.ACADEMIC_PROGRAM_MANAGE);

        leaderToken = UUID.randomUUID();
        leader = saveMember(leaderToken, "20260802", "스터디장");

        /*
         * ACADEMIC_PROGRAM_MANAGE가 없는 회원. 권한이 아예 없는 것이 아니라 '다른 권한만'
         * 가져야 인증만으로는 통과하지 못한다는 것이 드러난다(#136·#138 테스트와 같은 구성).
         */
        outsiderToken = UUID.randomUUID();
        grant(saveMember(outsiderToken, "20260803", "국원"), AuthorityCode.WORK_MANAGE);

        study = createProgram("알고리즘 스터디", "1주차", "2주차");

        // 팀원은 그 활동의 확정 참가자다 — '관계가 아예 없는 사람'이 아니라는 것이 요점이다
        teamMemberToken = UUID.randomUUID();
        MemberEntity teamMember = saveMember(teamMemberToken, "20260804", "팀원");
        eventParticipantRepository.saveAndFlush(
                EventParticipantEntity.register(
                        study.getEvent(),
                        teamMember,
                        EventParticipantStatus.CONFIRMED,
                        null,
                        manager));
    }

    // ------------------------------------------------------------------ 조회

    /*
     * 스터디장 본인은 자기 활동의 처리 이력을 본다 — 무엇을 고쳐야 하는지가 이 목록에만 있다.
     * 정렬은 처리 최신순(식별자 내림차순)이라 마지막 처리가 맨 위다.
     */
    @Test
    void getApprovalsAsLeaderReturnsHistoryNewestFirst() throws Exception {
        Long sessionId = submitSession(0, "2026-09-05", "1회차");
        requestRevision(sessionId, "출석 인원과 명단이 맞지 않습니다.");
        resubmitSession(sessionId, 0, "2026-09-05", "출석부까지 채운 1회차");
        approveSession(sessionId);

        mockMvc.perform(authorized(get(approvalsPath(study)), leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(2)))
                .andExpect(jsonPath("$.data[0].aprvPntCd").value("SESSION"))
                .andExpect(jsonPath("$.data[0].aprvSttsCd").value("APPROVED"))
                .andExpect(jsonPath("$.data[0].sessionId").value(sessionId))
                // 승인자는 요청 본문이 아니라 인증 주체에서 왔다(#136)
                .andExpect(jsonPath("$.data[0].aprvrMbrNm").value("학술국장"))
                // 승인은 통보할 것이 없어 사유가 비어 있다
                .andExpect(jsonPath("$.data[0].opnnCn").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data[0].aprvDt").isNotEmpty())
                .andExpect(jsonPath("$.data[1].aprvSttsCd").value("REVISION_REQUESTED"))
                .andExpect(jsonPath("$.data[1].opnnCn").value("출석 인원과 명단이 맞지 않습니다."))
                .andExpect(jsonPath("$.page.sort").value("-approvalId"))
                .andExpect(jsonPath("$.page.hasNext").value(false))
                .andExpect(jsonPath("$.page.totalCount").value(2))
                .andExpect(jsonPath("$.page.overallCount").value(2));
    }

    // 국장도 감독 목적으로 본다 — 소유권과 관리권한의 OR이다(#138과 같은 정책 한 곳)
    @Test
    void getApprovalsAsManagerReturns200() throws Exception {
        Long sessionId = submitSession(0, "2026-09-05", "1회차");
        approveSession(sessionId);

        mockMvc.perform(authorized(get(approvalsPath(study)), managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data[0].sessionId").value(sessionId));
    }

    // 아직 아무것도 처리되지 않은 활동은 빈 배열이다 — 정상적인 답이지 오류가 아니다
    @Test
    void getApprovalsWithoutAnyHistoryReturnsEmptyArray() throws Exception {
        mockMvc.perform(authorized(get(approvalsPath(study)), leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.page.totalCount").value(0))
                .andExpect(jsonPath("$.page.overallCount").value(0));
    }

    // 다른 활동의 이력은 섞이지 않는다 — 질의가 언제나 경로의 활동으로 먼저 좁힌다
    @Test
    void getApprovalsExcludesOtherProgramsHistory() throws Exception {
        Long mine = submitSession(0, "2026-09-05", "1회차");
        approveSession(mine);

        AcademicProgramEntity another = createProgram("남의 스터디", "킥오프");
        Long foreign = submitSession(another, 0, "2026-09-20", "킥오프");
        approveSession(another, foreign);

        mockMvc.perform(authorized(get(approvalsPath(study)), leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data[0].sessionId").value(mine))
                .andExpect(jsonPath("$.page.overallCount").value(1));
    }

    // ------------------------------------------------------------------ 필터

    /*
     * 지점 필터. COMPLETION은 활동 단위 승인이라 sessionId가 언제나 null이며(데이터모델 §2),
     * 분모(overallCount)는 필터와 무관하게 그 활동의 이력 전체 건수다.
     */
    @Test
    void filtersByApprovalPoint() throws Exception {
        Long sessionId = submitSession(0, "2026-09-05", "1회차");
        approveSession(sessionId);
        approveCompletion();

        mockMvc.perform(
                        authorized(get(approvalsPath(study)), managerToken)
                                .param("aprvPntCd", "COMPLETION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data[0].aprvPntCd").value("COMPLETION"))
                .andExpect(jsonPath("$.data[0].aprvSttsCd").value("APPROVED"))
                .andExpect(jsonPath("$.data[0].sessionId").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.page.totalCount").value(1))
                .andExpect(jsonPath("$.page.overallCount").value(2));

        mockMvc.perform(
                        authorized(get(approvalsPath(study)), managerToken)
                                .param("aprvPntCd", "SESSION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data[0].aprvPntCd").value("SESSION"))
                .andExpect(jsonPath("$.data[0].sessionId").value(sessionId));
    }

    // 회차 하나의 처리 기록만 좁혀 본다 — 회차 상세 화면의 '검토 기록'이 쓰는 필터다
    @Test
    void filtersBySessionId() throws Exception {
        Long first = submitSession(0, "2026-09-05", "1회차");
        Long second = submitSession(1, "2026-09-12", "2회차");
        approveSession(first);
        approveSession(second);

        mockMvc.perform(
                        authorized(get(approvalsPath(study)), leaderToken)
                                .param("sessionId", String.valueOf(second)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data[0].sessionId").value(second))
                .andExpect(jsonPath("$.page.totalCount").value(1))
                .andExpect(jsonPath("$.page.overallCount").value(2));
    }

    /*
     * **aprvPntCd는 SESSION·COMPLETION만 받는다.** 2026-08-24 재설계로 기획안 승인 이력은 폼
     * 응답 검토(#141)로 옮겨 갔고, 그 어휘는 AcademicProgramApprovalPoint에 아예 없다 — 옛
     * 이름으로 부르면 조용히 전건이 나오지 않고 400이다.
     */
    @Test
    void rejectsProposalApprovalPoint() throws Exception {
        mockMvc.perform(
                        authorized(get(approvalsPath(study)), managerToken)
                                .param("aprvPntCd", "PROPOSAL"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE_VALUE"));
    }

    @Test
    void rejectsUnknownApprovalPoint() throws Exception {
        mockMvc.perform(
                        authorized(get(approvalsPath(study)), managerToken)
                                .param("aprvPntCd", "무효"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE_VALUE"));
    }

    // ------------------------------------------------------------------ 페이징

    @Test
    void paginatesWithCursor() throws Exception {
        Long first = submitSession(0, "2026-09-05", "1회차");
        Long second = submitSession(1, "2026-09-12", "2회차");
        approveSession(first);
        approveSession(second);

        String firstPage =
                mockMvc.perform(
                                authorized(get(approvalsPath(study)), leaderToken)
                                        .param("size", "1"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data", Matchers.hasSize(1)))
                        // 최신순이므로 나중에 처리한 2회차가 먼저다
                        .andExpect(jsonPath("$.data[0].sessionId").value(second))
                        .andExpect(jsonPath("$.page.hasNext").value(true))
                        .andExpect(jsonPath("$.page.totalCount").value(2))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String cursor = JsonPath.parse(firstPage).read("$.page.nextCursor", String.class);

        mockMvc.perform(
                        authorized(get(approvalsPath(study)), leaderToken)
                                .param("size", "1")
                                .param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data[0].sessionId").value(first))
                .andExpect(jsonPath("$.page.hasNext").value(false));
    }

    @Test
    void rejectsMalformedCursor() throws Exception {
        mockMvc.perform(
                        authorized(get(approvalsPath(study)), leaderToken)
                                .param("cursor", "not-a-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ------------------------------------------------------------------ 열람 범위

    /*
     * **일반 팀원은 볼 수 없다.** 이 활동의 확정 참가자여도 마찬가지다 — 수정요청 사유는 활동
     * 운영진 개인에게 민감할 수 있으므로 열람을 스터디장 본인과 학술국장으로 좁혔다
     * (2026-08-22 2차 검증 확정). 상태값만 필요한 화면은 #134의 sessionSttsCd를 쓴다.
     */
    @Test
    void getApprovalsAsTeamMemberReturns403() throws Exception {
        Long sessionId = submitSession(0, "2026-09-05", "1회차");
        approveSession(sessionId);

        mockMvc.perform(authorized(get(approvalsPath(study)), teamMemberToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void getApprovalsAsOutsiderReturns403() throws Exception {
        Long sessionId = submitSession(0, "2026-09-05", "1회차");
        approveSession(sessionId);

        mockMvc.perform(authorized(get(approvalsPath(study)), outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // 다른 활동의 스터디장이라는 사실은 이 활동에서 아무 자격도 되지 않는다
    @Test
    void getApprovalsAsAnotherProgramsLeaderReturns403() throws Exception {
        UUID anotherLeaderToken = UUID.randomUUID();
        MemberEntity anotherLeader = saveMember(anotherLeaderToken, "20260805", "남의 스터디장");
        AcademicProgramFixture.save(
                eventRepository,
                eventClassificationRepository,
                academicProgramRepository,
                academicProgramTypeRepository,
                curriculumItemRepository,
                "STUDY",
                "남의 스터디",
                anotherLeader,
                List.of("킥오프"));

        mockMvc.perform(authorized(get(approvalsPath(study)), anotherLeaderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void getApprovalsWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get(approvalsPath(study))).andExpect(status().isUnauthorized());
    }

    /*
     * 없는 활동은 404다. 자격 판정보다 먼저 오므로 권한이 있는 요청자가 오타를 냈을 때
     * "권한이 없다"가 아니라 "그런 활동이 없다"로 읽힌다.
     */
    @Test
    void getApprovalsOfUnknownProgramReturns404() throws Exception {
        mockMvc.perform(authorized(get(PROGRAMS + "/999999/approvals"), managerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACADEMIC_PROGRAM_NOT_FOUND"));
    }

    // ------------------------------------------------------------------ 헬퍼

    private String approvalsPath(AcademicProgramEntity program) {
        return PROGRAMS + "/" + program.getId() + "/approvals";
    }

    private void approveSession(Long sessionId) throws Exception {
        approveSession(study, sessionId);
    }

    private void approveSession(AcademicProgramEntity program, Long sessionId) throws Exception {
        mockMvc.perform(
                        authorized(transitionPath(program, sessionId), managerToken)
                                .content("{\"transition\": \"APPROVE\"}"))
                .andExpect(status().isOk());
    }

    private void requestRevision(Long sessionId, String reason) throws Exception {
        mockMvc.perform(
                        authorized(transitionPath(study, sessionId), managerToken)
                                .content(
                                        "{\"transition\": \"REQUEST_REVISION\", \"reason\": \"%s\"}"
                                                .formatted(reason)))
                .andExpect(status().isOk());
    }

    private MockHttpServletRequestBuilder transitionPath(
            AcademicProgramEntity program, Long sessionId) {
        return post(PROGRAMS + "/" + program.getId() + "/sessions/" + sessionId + "/transitions");
    }

    /*
     * 종료 승인(#133)까지 밀어 COMPLETION 이력 한 건을 만든다. 상태만 심지 않고 전이 API를
     * 태우려면 모집을 먼저 시작해야 하고, 모집 시작은 문항이 있는 폼을 요구하므로 승인 후속
     * 처리(#133)와 문항 채우기를 함께 거친다 — AcademicProgramTransitionControllerTest가
     * ONGOING 활동을 만드는 방식과 같다.
     */
    private void approveCompletion() throws Exception {
        approvalEffectsService.applyPostApprovalEffects(study);
        addQuestionItem(study);
        entityManager.flush();

        transitionProgram("START_RECRUITMENT");
        transitionProgram("APPROVE_COMPLETION");
    }

    private void transitionProgram(String transition) throws Exception {
        mockMvc.perform(
                        authorized(
                                        post(PROGRAMS + "/" + study.getId() + "/transitions"),
                                        managerToken)
                                .content("{\"transition\": \"%s\"}".formatted(transition)))
                .andExpect(status().isOk());
    }

    private void addQuestionItem(AcademicProgramEntity program) {
        EventEntity event = eventRepository.findById(program.getEvent().getId()).orElseThrow();
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

    /*
     * 회차 실적은 #135의 제출 API를 그대로 태워 만든다 — 리포지토리로 심으면 승인이 실제
     * 저장 규칙을 지나치지 않은 값 위에서 돈다(#136 테스트와 같은 방식).
     */
    private Long submitSession(int curriculumIndex, String realDate, String content) {
        return submitSession(study, curriculumIndex, realDate, content);
    }

    private Long submitSession(
            AcademicProgramEntity program, int curriculumIndex, String realDate, String content) {
        try {
            String response =
                    mockMvc.perform(
                                    authorized(
                                                    post(
                                                            PROGRAMS
                                                                    + "/"
                                                                    + program.getId()
                                                                    + "/sessions"),
                                                    leaderToken)
                                            .content(
                                                    submitBody(
                                                            program,
                                                            curriculumIndex,
                                                            realDate,
                                                            content)))
                            .andExpect(status().isCreated())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            return JsonPath.parse(response).read("$.data.sessionId", Long.class);
        } catch (Exception ex) {
            throw new IllegalStateException("회차 제출 픽스처 실패", ex);
        }
    }

    private void resubmitSession(
            Long sessionId, int curriculumIndex, String realDate, String content) throws Exception {
        mockMvc.perform(
                        authorized(
                                        put(
                                                PROGRAMS
                                                        + "/"
                                                        + study.getId()
                                                        + "/sessions/"
                                                        + sessionId),
                                        leaderToken)
                                .content(submitBody(study, curriculumIndex, realDate, content)))
                .andExpect(status().isOk());
    }

    private String submitBody(
            AcademicProgramEntity program, int curriculumIndex, String realDate, String content) {
        CurriculumItemEntity item =
                curriculumItemRepository
                        .findByAcademicProgramIdOrderBySeqnoAsc(program.getId())
                        .get(curriculumIndex);
        return """
               {"curriculumItemId": %d, "realDt": "%s", "cn": "%s", "attendances": []}
               """
                .formatted(item.getId(), realDate, content);
    }

    private AcademicProgramEntity createProgram(String title, String... curriculumTitles) {
        return AcademicProgramFixture.save(
                eventRepository,
                eventClassificationRepository,
                academicProgramRepository,
                academicProgramTypeRepository,
                curriculumItemRepository,
                "STUDY",
                title,
                leader,
                List.of(curriculumTitles));
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

    private static MockHttpServletRequestBuilder authorized(
            MockHttpServletRequestBuilder builder, UUID authUserId) {
        return builder.header("Authorization", "Bearer " + authUserId)
                .contentType(MediaType.APPLICATION_JSON);
    }

    @TestConfiguration
    static class StubJwtDecoderConfig {

        @Bean
        @Primary
        JwtDecoder jwtDecoder() {
            return token ->
                    Jwt.withTokenValue(token)
                            .header("alg", "none")
                            .subject(token)
                            .claim("email", token + "@sscc.org")
                            .issuedAt(Instant.now())
                            .expiresAt(Instant.now().plusSeconds(60))
                            .build();
        }
    }
}
