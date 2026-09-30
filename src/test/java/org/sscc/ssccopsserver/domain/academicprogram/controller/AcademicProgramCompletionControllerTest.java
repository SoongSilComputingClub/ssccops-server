package org.sscc.ssccopsserver.domain.academicprogram.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramStatus;
import org.sscc.ssccopsserver.domain.academicprogram.entity.CurriculumItemEntity;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramTypeRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.CurriculumItemRepository;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.event.repository.EventClassificationRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventRepository;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
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

import com.jayway.jsonpath.JsonPath;

/*
 * 종료는 그 활동의 쓰기를 전부 멈춘다 (#597 · ADR-0057 · AcademicProgramWritePolicy). 폐지도 같다
 * (#611 · ADR-0058) — 같은 WritePath 표를 폐지 상태에도 돌린다(거절 코드만 다르다).
 *
 * **쓰기 경로 열(#597의 여덟 + #612의 팀원 추가·상태 변경)을 WritePath 한 표에 둔다.** 서버가 판정을 한 자리에 모은 것과 같은 이유다 —
 * 경로마다 테스트를 흩어 두면 새 쓰기 경로가 생길 때 그 테스트만 빠진다. 경로를 더하면 이 표에
 * 한 줄을 더하고, 두 파라미터화 테스트가 409와 «403이 409보다 먼저»를 함께 본다.
 *
 * 활동은 전부 API로 만든다 — 모집 시작(폼 OPEN) → 회차 제출 둘(하나는 수정요청) → 종료 승인.
 * 상태만 심으면 폼이 DRAFT로 남거나 회차가 «제출되지 않은 채 검토 대기»가 되어, 실제로 만들어질
 * 수 없는 상태로 가드를 보게 된다. 예외는 모집 폼 isEditable 하나이고 그 이유는 그 테스트에 있다.
 *
 * 실패를 기대하는 요청은 테스트마다 마지막에 한 번만 부른다 — 서비스가 @Transactional이라
 * 예외가 테스트 트랜잭션을 rollback-only로 표시한다(domain/academicprogram/AGENTS.md).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
@Transactional
class AcademicProgramCompletionControllerTest {

    private static final String PROGRAMS = "/v1/academic-programs";
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
    @Autowired private FormRepository formRepository;
    @Autowired private FormResponseHistoryRepository formResponseHistoryRepository;

    private UUID managerToken;
    private UUID leaderToken;
    private MemberEntity leader;
    private UUID outsiderToken;

    private AcademicProgramEntity program;
    private FormEntity recruitmentForm;

    /** 수정요청을 받은 회차 — 활동이 살아 있으면 재제출할 수 있는 자리다 */
    private Long sessionUnderRevision;

    /** 검토를 기다리는 회차 — 활동이 살아 있으면 승인·출석 정정·인증사진이 되는 자리다 */
    private Long sessionAwaitingReview;

    /** 실적이 아직 없는 계획 — 활동이 살아 있으면 새로 제출할 수 있는 자리다 */
    private Long vacantCurriculumItemId;

    @BeforeEach
    void setUp() throws Exception {
        managerToken = UUID.randomUUID();
        grant(saveMember(managerToken, "20260901", "학술국장"), AuthorityCode.ACADEMIC_PROGRAM_MANAGE);

        leaderToken = UUID.randomUUID();
        leader = saveMember(leaderToken, "20260902", "스터디장");

        // 권한이 아예 없는 것이 아니라 '다른 권한만' 가져야 인증만으로는 통과하지 못한다는 것이 드러난다
        outsiderToken = UUID.randomUUID();
        grant(saveMember(outsiderToken, "20260903", "국원"), AuthorityCode.WORK_MANAGE);

        program = createProgram("종료될 스터디");
        recruitmentForm = linkForm(program);
        transitionProgram(program, "START_RECRUITMENT");

        List<CurriculumItemEntity> items = curriculumItems(program);
        sessionUnderRevision = submitSession(items.get(0), "2026-09-05");
        requestRevision(sessionUnderRevision);
        sessionAwaitingReview = submitSession(items.get(1), "2026-09-12");
        vacantCurriculumItemId = items.get(2).getId();

        transitionProgram(program, "APPROVE_COMPLETION");
    }

    // ------------------------------------------------------------------ 쓰기 경로

    /*
     * 종료된 활동의 쓰기는 전부 409 ACADEMIC_PROGRAM_COMPLETED다. 경로마다 «활동이 살아 있으면
     * 통과할 요청»을 보낸다 — 본문이 틀려서 400이 먼저 나면 가드를 본 것이 아니다. 경로마다
     * 원래 걸리던 409(회차 상태·창·모집 시작)보다 이것이 먼저다.
     */
    @ParameterizedTest
    @EnumSource(WritePath.class)
    void completedProgramRejectsWrite(WritePath path) throws Exception {
        mockMvc.perform(authorized(path.request(this), tokenOf(path.qualification)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACADEMIC_PROGRAM_COMPLETED"));
    }

    /*
     * 자격이 없는 요청자에게는 «종료됐다»가 아니라 403이다 — 끝났다는 사실을 권한 없는 사람에게
     * 먼저 알리지 않는다. @RequireAuthority 경로는 애스펙트가, 소유권 경로는
     * AcademicProgramWritePolicy가 종료보다 먼저 끊는다.
     */
    @ParameterizedTest
    @EnumSource(WritePath.class)
    void forbiddenPrecedesCompleted(WritePath path) throws Exception {
        mockMvc.perform(authorized(path.request(this), outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // 재시작하면 같은 쓰기가 다시 된다 — 가드가 보는 것은 지금 상태 하나다
    @Test
    void reopenLetsWritesThroughAgain() throws Exception {
        transitionProgram(program, "REOPEN");

        mockMvc.perform(
                        authorized(
                                        post(sessionPath(sessionAwaitingReview) + "/transitions"),
                                        managerToken)
                                .content("{\"transition\": \"APPROVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.afterSttsCd").value("APPROVED"));
    }

    // ------------------------------------------------------------------ 폐지도 같은 표 (#611)

    /*
     * 폐지도 같은 쓰기 경로를 전부 멈춘다(ADR-0058) — 코드만 ACADEMIC_PROGRAM_DISCONTINUED로 다르다(화면이
     * «재시작»이 아니라 «복원»을 안내해야 한다). setUp이 종료까지 가 있으므로 재시작한 뒤 폐지한다 —
     * 종료에서는 폐지할 수 없다.
     */
    @ParameterizedTest
    @EnumSource(WritePath.class)
    void discontinuedProgramRejectsWrite(WritePath path) throws Exception {
        discontinueProgram();

        mockMvc.perform(authorized(path.request(this), tokenOf(path.qualification)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACADEMIC_PROGRAM_DISCONTINUED"));
    }

    // 폐지도 권한 없는 요청자에게는 403이 먼저다 — 멈췄다는 사실을 먼저 알리지 않는다
    @ParameterizedTest
    @EnumSource(WritePath.class)
    void forbiddenPrecedesDiscontinued(WritePath path) throws Exception {
        discontinueProgram();

        mockMvc.perform(authorized(path.request(this), outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // 복원하면 같은 쓰기가 다시 된다 — 진행 중에서 폐지했으므로 진행 중으로 돌아간다
    @Test
    void reinstateLetsWritesThroughAgain() throws Exception {
        discontinueProgram();
        transitionProgram(program, "REINSTATE");

        mockMvc.perform(
                        authorized(
                                        post(sessionPath(sessionAwaitingReview) + "/transitions"),
                                        managerToken)
                                .content("{\"transition\": \"APPROVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.afterSttsCd").value("APPROVED"));
    }

    // 계획 표의 편집 버튼과 승인 대기 목록도 종료와 같이 따라온다 — 둘 다 acceptsWrites를 본다
    @Test
    void discontinuedProgramTurnsOffCurriculumEditingAndLeavesTheReviewQueue() throws Exception {
        discontinueProgram();

        mockMvc.perform(authorized(get(programPath() + "/curriculum-items"), leaderToken))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data[*].isEditable", Matchers.everyItem(Matchers.is(false))));
        mockMvc.perform(authorized(get(REVIEW_SESSIONS), managerToken).param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath(
                                "$.data[*].sessionId",
                                Matchers.not(Matchers.hasItem(sessionAwaitingReview.intValue()))));
    }

    // ------------------------------------------------------------------ 막지 않는 것

    // 조회는 막지 않는다 — 회차 이력은 종료 뒤에도 그대로 보인다
    @Test
    void completedProgramStillServesReads() throws Exception {
        mockMvc.perform(authorized(get(programPath() + "/sessions"), leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(2)));
        mockMvc.perform(authorized(get(sessionPath(sessionAwaitingReview)), leaderToken))
                .andExpect(status().isOk());
        mockMvc.perform(
                        authorized(
                                get(sessionPath(sessionAwaitingReview) + "/attendances"),
                                leaderToken))
                .andExpect(status().isOk());
        mockMvc.perform(authorized(get(programPath() + "/recruitment/form"), leaderToken))
                .andExpect(status().isOk());
    }

    // 공유 링크는 내용을 바꾸지 않는다 — 발급·회수는 종료와 무관하다
    @Test
    void completedProgramStillIssuesAndRevokesShareLink() throws Exception {
        mockMvc.perform(authorized(post(programPath() + "/share"), leaderToken))
                .andExpect(status().isOk());
        mockMvc.perform(authorized(delete(programPath() + "/share"), leaderToken))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ 종료 시 폼 · isEditable

    // 종료는 접수 중이던 모집 폼을 같은 트랜잭션에서 마감한다
    @Test
    void completionClosedTheOpenRecruitmentForm() {
        entityManager.flush();
        entityManager.clear();
        assertThat(formRepository.findById(recruitmentForm.getId()).orElseThrow().getStatus())
                .isEqualTo(FormStatus.CLOSED);
    }

    /*
     * 계획 표의 편집 버튼은 종료된 활동에서 리더에게도 꺼진다. 비교 대상이 있어야 «꺼졌다»가
     * 말이 되므로 재시작 뒤와 나란히 본다 — 재시작하면 기록할 수 있는 두 줄(수정요청 · 미제출)이
     * 다시 켜지고 검토 대기 줄은 그대로 꺼져 있다(회차 상태의 판정은 그대로다).
     */
    @Test
    void curriculumIsNotEditableWhileCompleted() throws Exception {
        mockMvc.perform(authorized(get(programPath() + "/curriculum-items"), leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(3)))
                .andExpect(
                        jsonPath("$.data[*].isEditable", Matchers.everyItem(Matchers.is(false))));

        transitionProgram(program, "REOPEN");

        mockMvc.perform(authorized(get(programPath() + "/curriculum-items"), leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].sesnSttsCd").value("REVISION_REQUESTED"))
                .andExpect(jsonPath("$.data[0].isEditable").value(true))
                .andExpect(jsonPath("$.data[1].sesnSttsCd").value("SUBMITTED"))
                .andExpect(jsonPath("$.data[1].isEditable").value(false))
                .andExpect(jsonPath("$.data[2].sesnSttsCd").value("NOT_SUBMITTED"))
                .andExpect(jsonPath("$.data[2].isEditable").value(true));
    }

    /*
     * 모집 폼의 isEditable은 창과 활동 상태의 곱이다. 종료가 접수 중인 폼을 닫아 버리므로 API만
     * 태우면 창이 언제나 닫힌 채라 활동 쪽 인자를 따로 볼 수 없다 — 그래서 여기만 **창이 열린
     * (SCHEDULED) 활동의 상태를 벌크 UPDATE로 COMPLETED로 바꾼다.** 폼 화면에서 모집을 다시
     * 여는 것은 막지 않으므로(ADR-0057 «포기하는 것») 이 조합은 실제로 생길 수 있다.
     */
    @Test
    void recruitmentFormIsNotEditableWhileCompletedEvenInsideWindow() throws Exception {
        AcademicProgramEntity scheduled = createProgram("모집 예정 스터디");
        linkForm(scheduled);
        startRecruitmentAt(scheduled, laterBy(7), laterBy(14));

        mockMvc.perform(authorized(get(programPath(scheduled) + "/recruitment/form"), leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isEditable").value(true));

        markCompleted(scheduled);

        mockMvc.perform(authorized(get(programPath(scheduled) + "/recruitment/form"), leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.form.receiptStatus").value("SCHEDULED"))
                .andExpect(jsonPath("$.data.isEditable").value(false));
    }

    // ------------------------------------------------------------------ 승인 대기 목록

    /*
     * 종료된 활동의 제출 회차는 승인 대기에서 빠진다 — 국장이 처리할 수 없는 줄이 대기열을
     * 차지하지 않게. 재시작하면 다시 나온다. 회차 이력(위 조회 테스트)에서는 그대로 보인다.
     */
    @Test
    void reviewQueueExcludesCompletedProgramsSessionsUntilReopened() throws Exception {
        mockMvc.perform(authorized(get(REVIEW_SESSIONS), managerToken).param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath(
                                "$.data[*].sessionId",
                                Matchers.not(Matchers.hasItem(sessionAwaitingReview.intValue()))));

        transitionProgram(program, "REOPEN");

        mockMvc.perform(authorized(get(REVIEW_SESSIONS), managerToken).param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath(
                                "$.data[*].sessionId",
                                Matchers.hasItem(sessionAwaitingReview.intValue())));
    }

    // ------------------------------------------------------------------ 쓰기 경로 표

    /** 경로가 요구하는 자격 — 409 테스트는 이 자격을 가진 사람이, 403 테스트는 국원이 부른다 */
    private enum Qualification {
        LEADER,
        MANAGER,
        LEADER_OR_MANAGER
    }

    /*
     * 종료·폐지가 멈추는 쓰기 전부 (#597 이슈의 표 · #611). **새 쓰기 경로를 만들면 여기에 한 줄을
     * 더한다.** 막지 않는 것(조회 · 공유 링크 · 재시작·복원 전이)은 이 표에 없다.
     */
    private enum WritePath {
        SUBMIT_SESSION(Qualification.LEADER) {
            @Override
            MockHttpServletRequestBuilder request(AcademicProgramCompletionControllerTest test) {
                return post(test.programPath() + "/sessions")
                        .content(submitBody(test.vacantCurriculumItemId, "2026-09-19"));
            }
        },
        RESUBMIT_SESSION(Qualification.LEADER) {
            @Override
            MockHttpServletRequestBuilder request(AcademicProgramCompletionControllerTest test) {
                return put(test.sessionPath(test.sessionUnderRevision))
                        .content(submitBody(test.curriculumItemOf(0), "2026-09-06"));
            }
        },
        TRANSITION_SESSION(Qualification.MANAGER) {
            @Override
            MockHttpServletRequestBuilder request(AcademicProgramCompletionControllerTest test) {
                return post(test.sessionPath(test.sessionAwaitingReview) + "/transitions")
                        .content("{\"transition\": \"APPROVE\"}");
            }
        },
        CORRECT_ATTENDANCES(Qualification.LEADER) {
            @Override
            MockHttpServletRequestBuilder request(AcademicProgramCompletionControllerTest test) {
                // 출석부에 없는 대상이지만 400보다 종료가 먼저다
                return patch(test.sessionPath(test.sessionAwaitingReview) + "/attendances")
                        .content("{\"attendances\": [{\"eventPtcpId\": 1, \"atndYn\": true}]}");
            }
        },
        ISSUE_FILE_REFERENCE(Qualification.LEADER) {
            @Override
            MockHttpServletRequestBuilder request(AcademicProgramCompletionControllerTest test) {
                return post(test.sessionPath(test.sessionAwaitingReview) + "/file-reference")
                        .content("{\"fileExt\": \"jpg\", \"fileSize\": 1024}");
            }
        },
        SELECT_MEMBERS(Qualification.MANAGER) {
            @Override
            MockHttpServletRequestBuilder request(AcademicProgramCompletionControllerTest test) {
                return post(test.programPath() + "/recruitment/select")
                        .content(
                                "{\"selections\": [{\"formRspnsId\": 1, \"ptcpSttsCd\":"
                                        + " \"CONFIRMED\"}]}");
            }
        },
        UPDATE_RECRUITMENT_SCHEDULE(Qualification.MANAGER) {
            @Override
            MockHttpServletRequestBuilder request(AcademicProgramCompletionControllerTest test) {
                // 끝난 활동의 접수 창을 다시 여는 요청 — 이것이 막혀야 했던 자리다
                return patch(test.programPath() + "/recruitment/schedule")
                        .content(
                                "{\"rcptBgngDt\": \"%s\", \"rcptEndDt\": \"%s\"}"
                                        .formatted(laterBy(1), laterBy(30)));
            }
        },
        ADD_MEMBER(Qualification.LEADER_OR_MANAGER) {
            @Override
            MockHttpServletRequestBuilder request(AcademicProgramCompletionControllerTest test) {
                // 팀원 추가(#612) — 스터디장 자신도 동아리 회원이라 활동이 살아 있으면 통과한다
                return post(test.programPath() + "/members")
                        .content("{\"mbrId\": %d}".formatted(test.leader.getId()));
            }
        },
        CHANGE_MEMBER_STATUS(Qualification.LEADER_OR_MANAGER) {
            @Override
            MockHttpServletRequestBuilder request(AcademicProgramCompletionControllerTest test) {
                // 팀원 상태 변경(#612) — 명단에 없는 행이지만 404보다 종료·폐지가 먼저다
                return patch(test.programPath() + "/members/1")
                        .content("{\"ptcpSttsCd\": \"CANCELLED\"}");
            }
        },
        UPDATE_RECRUITMENT_FORM(Qualification.LEADER_OR_MANAGER) {
            @Override
            MockHttpServletRequestBuilder request(AcademicProgramCompletionControllerTest test) {
                return put(test.programPath() + "/recruitment/form")
                        .content(
                                "{\"qitemCpstCn\": {\"pages\": [{\"pageTtl\": \"기본 정보\"}],"
                                        + " \"qitems\": [{\"qitemId\": \"q1\", \"qitemLblNm\":"
                                        + " \"지원 동기\", \"qitemTypeCd\": \"LONG_TEXT\","
                                        + " \"reqYn\": true, \"pageSeq\": 0}]}}");
            }
        };

        private final Qualification qualification;

        WritePath(Qualification qualification) {
            this.qualification = qualification;
        }

        abstract MockHttpServletRequestBuilder request(
                AcademicProgramCompletionControllerTest test);
    }

    // ------------------------------------------------------------------ 헬퍼

    private UUID tokenOf(Qualification qualification) {
        return switch (qualification) {
            case LEADER, LEADER_OR_MANAGER -> leaderToken;
            case MANAGER -> managerToken;
        };
    }

    private String programPath() {
        return programPath(program);
    }

    private static String programPath(AcademicProgramEntity target) {
        return PROGRAMS + "/" + target.getId();
    }

    private String sessionPath(Long sessionId) {
        return programPath() + "/sessions/" + sessionId;
    }

    private Long curriculumItemOf(int index) {
        return curriculumItems(program).get(index).getId();
    }

    private List<CurriculumItemEntity> curriculumItems(AcademicProgramEntity target) {
        return curriculumItemRepository.findByAcademicProgramIdOrderBySeqnoAsc(target.getId());
    }

    private static String submitBody(Long curriculumItemId, String realDate) {
        return """
               {"curriculumItemId": %d, "actlYmd": "%s", "prgrsCn": "진행 내용", "attendances": []}
               """
                .formatted(curriculumItemId, realDate);
    }

    private Long submitSession(CurriculumItemEntity item, String realDate) throws Exception {
        String response =
                mockMvc.perform(
                                authorized(post(programPath() + "/sessions"), leaderToken)
                                        .content(submitBody(item.getId(), realDate)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.parse(response).read("$.data.sessionId", Long.class);
    }

    private void requestRevision(Long sessionId) throws Exception {
        mockMvc.perform(
                        authorized(post(sessionPath(sessionId) + "/transitions"), managerToken)
                                .content(
                                        """
                                        {"transition": "REQUEST_REVISION", "reason": "출석을 다시 확인해 주세요"}
                                        """))
                .andExpect(status().isOk());
    }

    private void transitionProgram(AcademicProgramEntity target, String transition)
            throws Exception {
        mockMvc.perform(
                        authorized(post(programPath(target) + "/transitions"), managerToken)
                                .content("{\"transition\": \"%s\"}".formatted(transition)))
                .andExpect(status().isOk());
    }

    // 종료된 setUp 상태에서 폐지까지 — 종료에서는 폐지할 수 없어 재시작을 거친다
    private void discontinueProgram() throws Exception {
        transitionProgram(program, "REOPEN");
        mockMvc.perform(
                        authorized(post(programPath() + "/transitions"), managerToken)
                                .content(
                                        """
                                        {"transition": "DISCONTINUE", "reason": "최소 인원 미달"}
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.afterSttsCd").value("DISCONTINUED"));
    }

    private void startRecruitmentAt(AcademicProgramEntity target, String beginAt, String endAt)
            throws Exception {
        mockMvc.perform(
                        authorized(post(programPath(target) + "/transitions"), managerToken)
                                .content(
                                        """
                                        {"transition": "START_RECRUITMENT",
                                         "recruitmentStartDt": "%s",
                                         "recruitmentEndDt": "%s"}
                                        """
                                                .formatted(beginAt, endAt)))
                .andExpect(status().isOk());
    }

    // 폼을 건드리지 않고 활동 상태만 바꾼다 — 쓰는 자리는 위 테스트 하나이며 이유는 그 주석에 있다
    private void markCompleted(AcademicProgramEntity target) {
        entityManager.flush();
        entityManager
                .createQuery(
                        "update AcademicProgramEntity p set p.status = :status where p.id = :id")
                .setParameter("status", AcademicProgramStatus.COMPLETED)
                .setParameter("id", target.getId())
                .executeUpdate();
        entityManager.clear();
    }

    /*
     * 고정 Clock을 두지 않고 지금을 기준으로 잡는다 — @MockitoBean Clock은 스프링 컨텍스트를
     * 하나 더 만든다(AcademicProgramRecruitmentControllerTest와 같은 이유).
     */
    private static String laterBy(int days) {
        return OffsetDateTime.now(ZoneOffset.ofHours(9))
                .plusDays(days)
                .truncatedTo(ChronoUnit.SECONDS)
                .toString();
    }

    private AcademicProgramEntity createProgram(String title) {
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
                leader,
                List.of("OT", "1주차", "2주차"));
    }

    /*
     * 문항이 하나 있는 DRAFT 모집 폼을 활동의 Event에 잇는다 — 승인 후속 처리(#133)가 만드는 빈
     * 폼으로는 모집을 시작할 수 없다(FORM_HAS_NO_QUESTION).
     */
    private FormEntity linkForm(AcademicProgramEntity target) {
        FormEntity form =
                formRepository.saveAndFlush(
                        FormEntity.create(
                                leader,
                                "모집 폼",
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
                                                        null))),
                                null,
                                null,
                                FormStatus.DRAFT));

        EventEntity event = target.getEvent();
        event.linkForm(form);
        eventRepository.saveAndFlush(event);
        return form;
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
}
