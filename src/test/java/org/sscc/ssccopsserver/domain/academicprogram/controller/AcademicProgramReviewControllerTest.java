package org.sscc.ssccopsserver.domain.academicprogram.controller;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramApprovalPoint;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramApprovalStatus;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.CurriculumItemEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.SessionEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.SessionStatus;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramApprovalRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramTypeRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.CurriculumItemRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.SessionRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventClassificationRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventRepository;
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

import com.jayway.jsonpath.JsonPath;

/*
 * 학술국장 전용 회차 검토 API (#136) — 승인·수정요청 전이와 활동 횡단 조회 둘.
 *
 * 세 엔드포인트 모두 ACADEMIC_PROGRAM_MANAGE라 AcademicProgramTransitionControllerTest와 같은
 * 방식(관리자·비관리자 토큰)으로 권한을 가른다. 회차 실적은 리포지토리로 심지 않고 #135의
 * 제출 API를 그대로 태워 만든다 — 스터디장 본인만 쓸 수 있으므로 leader 토큰을 따로 둔다.
 *
 * 실패를 기대하는 요청은 테스트마다 마지막에 한 번만 부른다 — 서비스가 @Transactional이라
 * 예외가 테스트 트랜잭션을 rollback-only로 표시하고, 그 뒤 이어지는 요청은
 * UnexpectedRollbackException을 만난다(AGENTS.md).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AcademicProgramReviewControllerTest.StubJwtDecoderConfig.class)
@Transactional
class AcademicProgramReviewControllerTest {

    private static final String PROGRAMS = "/v1/academic-programs";
    private static final String CROSS_SESSIONS = PROGRAMS + "/sessions";
    private static final String REVIEW_SESSIONS = PROGRAMS + "/reviews/sessions";

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
    @Autowired private FormRepository formRepository;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private AcademicProgramApprovalRepository academicProgramApprovalRepository;

    private UUID managerToken;
    private MemberEntity manager;
    private UUID leaderToken;
    private MemberEntity leader;
    private UUID outsiderToken;

    private AcademicProgramEntity study;
    private AcademicProgramEntity project;

    @BeforeEach
    void setUp() {
        managerToken = UUID.randomUUID();
        manager = saveMember(managerToken, "20260601", "학술국장");
        grant(manager, AuthorityCode.ACADEMIC_PROGRAM_MANAGE);

        leaderToken = UUID.randomUUID();
        leader = saveMember(leaderToken, "20260602", "스터디장");

        /*
         * ACADEMIC_PROGRAM_MANAGE가 없는 회원. 권한이 아예 없는 것이 아니라 '다른 권한만'
         * 가져야 인증만으로는 통과하지 못한다는 것이 드러난다(#133 테스트와 같은 구성).
         */
        outsiderToken = UUID.randomUUID();
        grant(saveMember(outsiderToken, "20260603", "국원"), AuthorityCode.WORK_MANAGE);

        study = createAcademicProgram("STUDY", "알고리즘 스터디", "OT", "1주차");
        project = createAcademicProgram("PROJECT", "졸업 프로젝트", "킥오프");
    }

    // ------------------------------------------------------------------ 승인·수정요청 전이

    @Test
    void approveSessionReturnsBeforeAndAfterStatus() throws Exception {
        Long sessionId = submitSession(study, 0, "2026-09-05", "1회차");

        mockMvc.perform(
                        transition(study, sessionId)
                                .content(
                                        """
                        {"transition": "APPROVE"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionId").value(sessionId))
                .andExpect(jsonPath("$.data.beforeSttsCd").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.afterSttsCd").value("APPROVED"));

        assertThat(statusOf(sessionId)).isEqualTo(SessionStatus.APPROVED);
    }

    /*
     * 승인은 상태만 바꾸는 것이 아니라 acdm_actv_aprv에 처리 한 건을 남긴다 — 이 행이
     * "누가 언제 승인했는가"의 유일한 기록이다(sesn 행에는 감사 컬럼이 없다).
     */
    @Test
    void approveSessionRecordsApprovalRow() throws Exception {
        Long sessionId = submitSession(study, 0, "2026-09-05", "1회차");

        mockMvc.perform(
                        transition(study, sessionId)
                                .content(
                                        """
                        {"transition": "APPROVE"}
                        """))
                .andExpect(status().isOk());

        entityManager.flush();
        assertThat(academicProgramApprovalRepository.findAll())
                .singleElement()
                .satisfies(
                        approval -> {
                            assertThat(approval.getPoint())
                                    .isEqualTo(AcademicProgramApprovalPoint.SESSION);
                            assertThat(approval.getStatus())
                                    .isEqualTo(AcademicProgramApprovalStatus.APPROVED);
                            assertThat(approval.getSession().getId()).isEqualTo(sessionId);
                            assertThat(approval.getAcademicProgram().getId())
                                    .isEqualTo(study.getId());
                            // 승인자는 요청 본문이 아니라 인증 주체에서 온다
                            assertThat(approval.getApprover().getId()).isEqualTo(manager.getId());
                            // 대기 없이 곧바로 확정되므로 처리 일시가 비어 있지 않다
                            assertThat(approval.getApprovedAt()).isNotNull();
                            assertThat(approval.getOpinionContent()).isNull();
                        });
    }

    /*
     * 수정요청의 사유는 acdm_actv_aprv의 최신 행에만 남고, 회차 상세(#135)가 그것을
     * latestOpinion으로 읽는다 — 두 이슈가 이 한 컬럼으로 이어진다.
     */
    @Test
    void requestRevisionCarriesReasonToSessionDetail() throws Exception {
        Long sessionId = submitSession(study, 0, "2026-09-05", "1회차");

        mockMvc.perform(
                        transition(study, sessionId)
                                .content(
                                        """
                                        {"transition": "REQUEST_REVISION",
                                         "reason": "출석 인원과 명단이 맞지 않습니다."}
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.beforeSttsCd").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.afterSttsCd").value("REVISION_REQUESTED"));

        mockMvc.perform(
                        authorized(
                                get(PROGRAMS + "/" + study.getId() + "/sessions/" + sessionId),
                                leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sttsCd").value("REVISION_REQUESTED"))
                .andExpect(jsonPath("$.data.latestOpinion").value("출석 인원과 명단이 맞지 않습니다."));
    }

    // 수정요청은 "무엇을 고쳐야 하는가"를 알리는 통보라 사유 없이 성립하지 않는다
    @Test
    void requestRevisionWithoutReasonReturns400() throws Exception {
        Long sessionId = submitSession(study, 0, "2026-09-05", "1회차");

        mockMvc.perform(
                        transition(study, sessionId)
                                .content(
                                        """
                                        {"transition": "REQUEST_REVISION", "reason": "   "}
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REVISION_REASON_REQUIRED"));
    }

    // 승인은 통보할 것이 없으므로 사유가 선택이다
    @Test
    void approveSessionWithoutReasonSucceeds() throws Exception {
        Long sessionId = submitSession(study, 0, "2026-09-05", "1회차");

        mockMvc.perform(
                        transition(study, sessionId)
                                .content(
                                        """
                        {"transition": "APPROVE"}
                        """))
                .andExpect(status().isOk());
    }

    // 승인된 회차는 확정 이력이라 되돌리지 않는다 — 재승인도 수정요청도 막힌다
    @Test
    void transitionApprovedSessionReturns409() throws Exception {
        Long sessionId = submitSession(study, 0, "2026-09-05", "1회차");
        mockMvc.perform(
                        transition(study, sessionId)
                                .content(
                                        """
                        {"transition": "APPROVE"}
                        """))
                .andExpect(status().isOk());

        mockMvc.perform(
                        transition(study, sessionId)
                                .content(
                                        """
                                        {"transition": "REQUEST_REVISION", "reason": "다시 보니 부족합니다."}
                                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_SESSION_TRANSITION"));
    }

    /*
     * 이미 수정요청한 회차도 SUBMITTED가 아니다 — 다음 판정은 스터디장이 재제출(#135)해
     * SUBMITTED로 돌아온 뒤에 한다.
     */
    @Test
    void transitionRevisionRequestedSessionReturns409() throws Exception {
        Long sessionId = submitSession(study, 0, "2026-09-05", "1회차");
        mockMvc.perform(
                        transition(study, sessionId)
                                .content(
                                        """
                                        {"transition": "REQUEST_REVISION", "reason": "출석부를 채워 주세요."}
                                        """))
                .andExpect(status().isOk());

        mockMvc.perform(
                        transition(study, sessionId)
                                .content(
                                        """
                        {"transition": "APPROVE"}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_SESSION_TRANSITION"));
    }

    // 다른 활동 경로로 부르면 404다 — 회차 식별자만 보면 남의 활동 회차를 승인할 수 있다
    @Test
    void transitionSessionThroughAnotherProgramReturns404() throws Exception {
        Long sessionId = submitSession(study, 0, "2026-09-05", "1회차");

        mockMvc.perform(
                        transition(project, sessionId)
                                .content(
                                        """
                        {"transition": "APPROVE"}
                        """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
    }

    @Test
    void transitionSessionOfUnknownProgramReturns404() throws Exception {
        mockMvc.perform(
                        authorized(post(PROGRAMS + "/999999/sessions/1/transitions"), managerToken)
                                .content(
                                        """
                                        {"transition": "APPROVE"}
                                        """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACADEMIC_PROGRAM_NOT_FOUND"));
    }

    // 기준 코드 밖의 전이 액션은 enum 역직렬화 단계에서 걸린다
    @Test
    void transitionWithUnknownActionReturns400() throws Exception {
        Long sessionId = submitSession(study, 0, "2026-09-05", "1회차");

        mockMvc.perform(
                        transition(study, sessionId)
                                .content(
                                        """
                        {"transition": "CANCEL"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE_VALUE"));
    }

    /*
     * 스터디장 본인이어도 자기 회차를 승인할 수는 없다 — 기록을 쓰는 자격(소유권)과 승인하는
     * 자격(ACADEMIC_PROGRAM_MANAGE)은 다른 층이다.
     */
    @Test
    void transitionSessionAsLeaderReturns403() throws Exception {
        Long sessionId = submitSession(study, 0, "2026-09-05", "1회차");

        mockMvc.perform(
                        authorized(
                                        post(
                                                PROGRAMS
                                                        + "/"
                                                        + study.getId()
                                                        + "/sessions/"
                                                        + sessionId
                                                        + "/transitions"),
                                        leaderToken)
                                .content(
                                        """
                                        {"transition": "APPROVE"}
                                        """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void transitionSessionWithoutTokenReturns401() throws Exception {
        mockMvc.perform(
                        post(PROGRAMS + "/1/sessions/1/transitions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"transition": "APPROVE"}
                                        """))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ 회차 이력 (활동 횡단)

    /*
     * 활동이 여럿이어도 한 목록으로 합쳐 나온다 — 이 API가 있는 이유 자체다(설계 결정 #1).
     * 기본 정렬은 진행일 내림차순이라 활동 경계와 무관하게 최근 회차가 앞에 온다.
     */
    @Test
    void searchCrossSessionsMergesSessionsOfEveryProgram() throws Exception {
        submitSession(study, 0, "2026-09-05", "스터디 1회차");
        submitSession(project, 0, "2026-09-20", "프로젝트 킥오프");

        mockMvc.perform(authorized(get(CROSS_SESSIONS), managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(2)))
                .andExpect(jsonPath("$.data[0].actlYmd").value("2026-09-20"))
                .andExpect(jsonPath("$.data[0].academicProgramId").value(project.getId()))
                .andExpect(jsonPath("$.data[0].academicProgramTitle").value("졸업 프로젝트"))
                .andExpect(jsonPath("$.data[0].typeCd").value("PROJECT"))
                .andExpect(jsonPath("$.data[0].seqno").value(1))
                .andExpect(jsonPath("$.data[0].curriculumTtl").value("킥오프"))
                .andExpect(jsonPath("$.data[0].sttsCd").value("SUBMITTED"))
                .andExpect(jsonPath("$.data[0].presentCount").value(0))
                .andExpect(jsonPath("$.data[0].totalCount").value(0))
                // 인증사진(#137)은 아직 채우는 쪽이 없어 늘 false다
                .andExpect(jsonPath("$.data[0].hasFileReference").value(false))
                .andExpect(jsonPath("$.data[1].academicProgramTitle").value("알고리즘 스터디"))
                .andExpect(jsonPath("$.data[1].typeCd").value("STUDY"))
                .andExpect(jsonPath("$.page.sort").value("-actlYmd"))
                .andExpect(jsonPath("$.page.hasNext").value(false))
                .andExpect(jsonPath("$.page.totalCount").value(2))
                .andExpect(jsonPath("$.page.overallCount").value(2));
    }

    // keyword는 활동명과 회차 주제를 함께 훑는다 — 검색창이 하나이기 때문이다
    @Test
    void searchCrossSessionsFindsByProgramTitleOrCurriculumTitle() throws Exception {
        submitSession(study, 0, "2026-09-05", "스터디 1회차");
        submitSession(project, 0, "2026-09-20", "프로젝트 킥오프");

        mockMvc.perform(authorized(get(CROSS_SESSIONS), managerToken).param("keyword", "알고리즘"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data[0].academicProgramTitle").value("알고리즘 스터디"));

        mockMvc.perform(authorized(get(CROSS_SESSIONS), managerToken).param("keyword", "킥오프"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data[0].curriculumTtl").value("킥오프"))
                // 필터가 걸려도 분모(overallCount)는 전체 회차 수다
                .andExpect(jsonPath("$.page.totalCount").value(1))
                .andExpect(jsonPath("$.page.overallCount").value(2));
    }

    // like 와일드카드는 검색어이지 문법이 아니다 — 이스케이프되지 않으면 '%'가 전건을 부른다
    @Test
    void searchCrossSessionsTreatsWildcardAsLiteral() throws Exception {
        submitSession(study, 0, "2026-09-05", "스터디 1회차");

        mockMvc.perform(authorized(get(CROSS_SESSIONS), managerToken).param("keyword", "%"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void searchCrossSessionsNarrowsByAcademicProgramId() throws Exception {
        submitSession(study, 0, "2026-09-05", "스터디 1회차");
        submitSession(project, 0, "2026-09-20", "프로젝트 킥오프");

        mockMvc.perform(
                        authorized(get(CROSS_SESSIONS), managerToken)
                                .param("academicProgramId", String.valueOf(study.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data[0].academicProgramId").value(study.getId()));
    }

    @Test
    void searchCrossSessionsFiltersByStatus() throws Exception {
        Long approved = submitSession(study, 0, "2026-09-05", "스터디 1회차");
        submitSession(project, 0, "2026-09-20", "프로젝트 킥오프");
        mockMvc.perform(
                        transition(study, approved)
                                .content(
                                        """
                        {"transition": "APPROVE"}
                        """))
                .andExpect(status().isOk());

        mockMvc.perform(authorized(get(CROSS_SESSIONS), managerToken).param("sttsCd", "APPROVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data[0].sessionId").value(approved))
                .andExpect(jsonPath("$.page.totalCount").value(1))
                .andExpect(jsonPath("$.page.overallCount").value(2));
    }

    // 커서는 활동을 가로질러도 같은 규칙이다 — 정렬 키가 같은 건은 식별자로 끊는다
    @Test
    void searchCrossSessionsPaginatesWithCursor() throws Exception {
        submitSession(study, 0, "2026-09-05", "스터디 1회차");
        submitSession(project, 0, "2026-09-20", "프로젝트 킥오프");

        String firstPage =
                mockMvc.perform(authorized(get(CROSS_SESSIONS), managerToken).param("size", "1"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data", Matchers.hasSize(1)))
                        .andExpect(jsonPath("$.data[0].actlYmd").value("2026-09-20"))
                        .andExpect(jsonPath("$.page.hasNext").value(true))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String cursor = JsonPath.parse(firstPage).read("$.page.nextCursor", String.class);

        mockMvc.perform(
                        authorized(get(CROSS_SESSIONS), managerToken)
                                .param("size", "1")
                                .param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data[0].actlYmd").value("2026-09-05"))
                .andExpect(jsonPath("$.page.hasNext").value(false));
    }

    @Test
    void searchCrossSessionsWithUnknownSortReturns400() throws Exception {
        mockMvc.perform(authorized(get(CROSS_SESSIONS), managerToken).param("sort", "무효"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE_VALUE"));
    }

    /*
     * 활동을 가로지르는 목록은 남의 활동 회차를 그대로 보여주므로 조회도 국장 전용이다 —
     * 여기를 "인증만"으로 열면 활동 상세에 걸어 둔 경계가 이 경로 하나로 무의미해진다.
     */
    @Test
    void searchCrossSessionsWithoutAuthorityReturns403() throws Exception {
        mockMvc.perform(authorized(get(CROSS_SESSIONS), outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void searchCrossSessionsWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get(CROSS_SESSIONS)).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ 승인 대기 목록

    /*
     * 승인 대기 목록은 SUBMITTED만 모은다. 상태 필터를 받지 않으므로 이 목록에 승인·수정요청된
     * 회차가 섞일 길이 없다.
     */
    @Test
    void searchPendingSessionsReturnsOnlySubmittedAcrossPrograms() throws Exception {
        Long approved = submitSession(study, 0, "2026-09-05", "스터디 1회차");
        Long pendingStudy = submitSession(study, 1, "2026-09-12", "스터디 2회차");
        Long pendingProject = submitSession(project, 0, "2026-09-20", "프로젝트 킥오프");
        mockMvc.perform(
                        transition(study, approved)
                                .content(
                                        """
                        {"transition": "APPROVE"}
                        """))
                .andExpect(status().isOk());

        mockMvc.perform(authorized(get(REVIEW_SESSIONS), managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(2)))
                // 기본 정렬은 진행일 오름차순 — 오래 기다린 건부터 처리한다
                .andExpect(jsonPath("$.data[0].sessionId").value(pendingStudy))
                .andExpect(jsonPath("$.data[0].sttsCd").value("SUBMITTED"))
                .andExpect(jsonPath("$.data[1].sessionId").value(pendingProject))
                .andExpect(jsonPath("$.data[1].academicProgramTitle").value("졸업 프로젝트"))
                .andExpect(jsonPath("$.page.sort").value("actlYmd"))
                .andExpect(jsonPath("$.page.totalCount").value(2))
                // 분모는 대기 건수가 아니라 회차 전체 건수다
                .andExpect(jsonPath("$.page.overallCount").value(3));
    }

    /*
     * 수정요청한 회차는 대기 목록에서 빠지고, 스터디장이 재제출(#135)하면 다시 들어온다 —
     * 이 목록의 정의가 "SUBMITTED"라는 상태 하나로 끝난다는 뜻이다.
     */
    @Test
    void requestedRevisionLeavesPendingListUntilResubmitted() throws Exception {
        Long sessionId = submitSession(study, 0, "2026-09-05", "1회차");
        mockMvc.perform(
                        transition(study, sessionId)
                                .content(
                                        """
                                        {"transition": "REQUEST_REVISION", "reason": "출석부를 채워 주세요."}
                                        """))
                .andExpect(status().isOk());

        mockMvc.perform(authorized(get(REVIEW_SESSIONS), managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());

        resubmitSession(study, sessionId, 0, "2026-09-05", "출석부까지 채운 1회차");

        mockMvc.perform(authorized(get(REVIEW_SESSIONS), managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data[0].sessionId").value(sessionId));
    }

    @Test
    void searchPendingSessionsWithoutAuthorityReturns403() throws Exception {
        mockMvc.perform(authorized(get(REVIEW_SESSIONS), outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void searchPendingSessionsWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get(REVIEW_SESSIONS)).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ 헬퍼

    private MockHttpServletRequestBuilder transition(
            AcademicProgramEntity program, Long sessionId) {
        return authorized(
                post(PROGRAMS + "/" + program.getId() + "/sessions/" + sessionId + "/transitions"),
                managerToken);
    }

    /*
     * 회차 실적은 #135의 제출 API를 그대로 태워 만든다 — 리포지토리로 심으면 이 이슈가 다루는
     * 상태(SUBMITTED)가 실제 저장 규칙을 지나친 값이 된다.
     */
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
            AcademicProgramEntity program,
            Long sessionId,
            int curriculumIndex,
            String realDate,
            String content) {
        try {
            mockMvc.perform(
                            authorized(
                                            put(
                                                    PROGRAMS
                                                            + "/"
                                                            + program.getId()
                                                            + "/sessions/"
                                                            + sessionId),
                                            leaderToken)
                                    .content(
                                            submitBody(
                                                    program, curriculumIndex, realDate, content)))
                    .andExpect(status().isOk());
        } catch (Exception ex) {
            throw new IllegalStateException("회차 재제출 픽스처 실패", ex);
        }
    }

    private String submitBody(
            AcademicProgramEntity program, int curriculumIndex, String realDate, String content) {
        CurriculumItemEntity item = curriculumItems(program).get(curriculumIndex);
        return """
               {"curriculumItemId": %d, "actlYmd": "%s", "prgrsCn": "%s", "attendances": []}
               """
                .formatted(item.getId(), realDate, content);
    }

    private SessionStatus statusOf(Long sessionId) {
        entityManager.flush();
        return sessionRepository.findById(sessionId).map(SessionEntity::getStatus).orElseThrow();
    }

    private List<CurriculumItemEntity> curriculumItems(AcademicProgramEntity program) {
        return curriculumItemRepository.findByAcademicProgramIdOrderBySeqnoAsc(program.getId());
    }

    private AcademicProgramEntity createAcademicProgram(
            String typeCd, String title, String... curriculumTitles) {
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
