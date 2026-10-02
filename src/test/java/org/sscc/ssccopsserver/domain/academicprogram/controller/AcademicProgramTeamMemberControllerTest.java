package org.sscc.ssccopsserver.domain.academicprogram.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.CurriculumItemEntity;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramTypeRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.CurriculumItemRepository;
import org.sscc.ssccopsserver.domain.event.code.EventParticipantChangePath;
import org.sscc.ssccopsserver.domain.event.code.EventParticipantStatus;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.event.entity.EventParticipantEntity;
import org.sscc.ssccopsserver.domain.event.entity.EventParticipantStatusHistoryEntity;
import org.sscc.ssccopsserver.domain.event.repository.EventClassificationRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventParticipantRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventParticipantStatusHistoryRepository;
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
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

import com.jayway.jsonpath.JsonPath;

/*
 * 스터디장·학술국장의 팀원 관리 (#612 · ssccops#553 · POST·PATCH .../members · GET .../members/history).
 *
 * 모집 뒤 개인 사정으로 빠지는 팀원이 있어 스터디장이 명단을 직접 고친다 — **신청서 없이** 동아리
 * 회원 누구나 넣고, 빼는 것은 참가 취소이며, 빠졌던 사람은 재합류한다. 학술국장 승인 없이 바로
 * 반영되고 이력(event_ptcp_stts_hstry)이 남는다.
 *
 * 종료·폐지가 이 두 쓰기를 멈추는지(409)와 «403이 409보다 먼저»는 쓰기 경로 표
 * (AcademicProgramCompletionControllerTest.WritePath)가 본다 — 여기서 다시 적지 않는다.
 *
 * 활동은 API로 모집을 시작해 진행 중으로 만든다 — 상태만 심으면 폼이 DRAFT로 남아 선발 경로를 볼 수
 * 없다. 실패를 기대하는 요청은 테스트마다 마지막에 한 번만 부른다(학술 AGENTS.md — rollback-only).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
@Transactional
class AcademicProgramTeamMemberControllerTest {

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
    @Autowired private EventParticipantStatusHistoryRepository historyRepository;
    @Autowired private AcademicProgramRepository academicProgramRepository;
    @Autowired private AcademicProgramTypeRepository academicProgramTypeRepository;
    @Autowired private CurriculumItemRepository curriculumItemRepository;
    @Autowired private FormRepository formRepository;
    @Autowired private FormResponseHistoryRepository formResponseHistoryRepository;

    private UUID managerToken;
    private UUID leaderToken;
    private MemberEntity leader;
    private UUID outsiderToken;
    private MemberEntity candidate;

    private AcademicProgramEntity program;
    private FormEntity recruitmentForm;

    private int studentNumberSeq = 0;

    @BeforeEach
    void setUp() throws Exception {
        managerToken = UUID.randomUUID();
        grant(saveMember(managerToken, "학술국장"), AuthorityCode.ACADEMIC_PROGRAM_MANAGE);

        leaderToken = UUID.randomUUID();
        leader = saveMember(leaderToken, "스터디장");

        // 권한이 아예 없는 것이 아니라 '다른 권한만' 가져야 인증만으로는 통과하지 못한다는 것이 드러난다
        outsiderToken = UUID.randomUUID();
        grant(saveMember(outsiderToken, "국원"), AuthorityCode.WORK_MANAGE);

        candidate = saveMember(UUID.randomUUID(), "늦게 합류하는 회원");

        program = createProgram("팀원 관리 스터디");
        recruitmentForm = linkForm(program);
        transitionProgram(program, "START_RECRUITMENT");
    }

    // ------------------------------------------------------------------ 추가

    /*
     * 스터디장이 신청서 없이 회원을 넣는다 — 확정으로 들어가고 참가 행의 신청 근거(form_rspns_id)는
     * 비며, 등록 처리자는 스터디장이다. 명단 조회의 isEditable은 스터디장에게 참이다.
     */
    @Test
    void leaderAddsMemberWithoutApplication() throws Exception {
        mockMvc.perform(addRequest(program, candidate, leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mbrId").value(candidate.getId()))
                .andExpect(jsonPath("$.data.ptcpSttsCd").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.isEditable").value(true));

        mockMvc.perform(authorized(get(membersPath(program)), leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data[0].mbrNm").value("늦게 합류하는 회원"))
                .andExpect(jsonPath("$.data[0].isEditable").value(true));

        EventParticipantEntity participant = participantOf(candidate);
        assertThat(participant.getFormResponse()).isNull();
        assertThat(participant.getRegistrant().getId()).isEqualTo(leader.getId());
    }

    // 학술국장도 추가할 수 있다 — 자격은 «스터디장 본인 또는 학술국장»이다
    @Test
    void managerAddsMemberToo() throws Exception {
        mockMvc.perform(addRequest(program, candidate, managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ptcpSttsCd").value("CONFIRMED"));
    }

    @Test
    void addingSomeoneAlreadyOnTheTeamReturns409() throws Exception {
        mockMvc.perform(addRequest(program, candidate, leaderToken)).andExpect(status().isOk());

        mockMvc.perform(addRequest(program, candidate, leaderToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_PARTICIPANT_DUPLICATED"));
    }

    // 탈퇴·제명 회원은 담당자 후보(/v1/members/assignable)와 같은 규칙으로 막힌다
    @Test
    void addingWithdrawnMemberReturns400() throws Exception {
        MemberEntity withdrawn =
                saveMember(UUID.randomUUID(), "탈퇴한 회원", MemberStatusCode.WITHDRAWN);

        mockMvc.perform(addRequest(program, withdrawn, leaderToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_ADDABLE"));
    }

    // 없는 회원도 같은 400이다 — 나누면 번호를 바꿔 부르는 것만으로 누가 탈퇴했는지가 새어 나간다
    @Test
    void addingUnknownMemberReturns400() throws Exception {
        mockMvc.perform(
                        authorized(post(membersPath(program)), leaderToken)
                                .content("{\"mbrId\": 999999}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_ADDABLE"));
    }

    // 남의 활동에는 넣을 수 없다 — 스터디장 자격은 활동 단위(leadr_mbr_id)다
    @Test
    void addingToAnotherLeadersProgramIsForbidden() throws Exception {
        mockMvc.perform(addRequest(program, candidate, outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // 모집 시작 전에는 팀원을 다루지 않는다 — 팀원은 모집으로 처음 생긴다
    @Test
    void addingBeforeRecruitmentStartsReturns409() throws Exception {
        AcademicProgramEntity approved = createProgram("모집 전 스터디");

        mockMvc.perform(addRequest(approved, candidate, leaderToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECRUITMENT_NOT_STARTED"));
    }

    // ------------------------------------------------------------------ 상태 변경 · 재합류

    /*
     * 제외는 참가 취소이고 행은 남는다. 빠졌던 사람은 PATCH(취소 → 확정)로 재합류하며 **같은 행**이
     * 되살아난다 — 행사 참가자 API의 전이표는 취소에서 나가는 길을 막지만 학술 경로만 연다.
     */
    @Test
    void leaderExcludesMemberThenRejoinsThemOnTheSameRow() throws Exception {
        Long eventPtcpId = addAndGetId(candidate);

        mockMvc.perform(statusRequest(program, eventPtcpId, "CANCELLED", leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ptcpSttsCd").value("CANCELLED"));
        mockMvc.perform(statusRequest(program, eventPtcpId, "CONFIRMED", leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eventPtcpId").value(eventPtcpId))
                .andExpect(jsonPath("$.data.ptcpSttsCd").value("CONFIRMED"));
    }

    // 제외된 사람을 «추가»로 다시 넣어도 재합류다 — 새 행이 아니라 옛 행이 돌아온다
    @Test
    void addingAnExcludedMemberRejoinsThem() throws Exception {
        Long eventPtcpId = addAndGetId(candidate);
        mockMvc.perform(statusRequest(program, eventPtcpId, "CANCELLED", leaderToken))
                .andExpect(status().isOk());

        mockMvc.perform(addRequest(program, candidate, leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eventPtcpId").value(eventPtcpId))
                .andExpect(jsonPath("$.data.ptcpSttsCd").value("CONFIRMED"));
    }

    // 확정 ↔ 대기 왕복은 행사 도메인의 전이표 그대로다
    @Test
    void leaderDemotesAndPromotesMember() throws Exception {
        Long eventPtcpId = addAndGetId(candidate);

        mockMvc.perform(statusRequest(program, eventPtcpId, "WAITLISTED", leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ptcpSttsCd").value("WAITLISTED"));
        mockMvc.perform(statusRequest(program, eventPtcpId, "CONFIRMED", leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ptcpSttsCd").value("CONFIRMED"));
    }

    // 전이표 밖(같은 상태로의 재지정)은 400이다 — 재합류를 열었다고 표가 넓어지지 않는다
    @Test
    void transitionOutsideTheTableReturns400() throws Exception {
        Long eventPtcpId = addAndGetId(candidate);

        mockMvc.perform(statusRequest(program, eventPtcpId, "CONFIRMED", leaderToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARTICIPANT_STATUS_TRANSITION"));
    }

    // 남의 활동의 명단 행 번호는 404다 — 명단 행을 이 활동의 행사로 좁혀 찾는다
    @Test
    void changingAnotherProgramsParticipantReturns404() throws Exception {
        AcademicProgramEntity other = createProgram("다른 스터디");
        linkForm(other);
        transitionProgram(other, "START_RECRUITMENT");
        mockMvc.perform(addRequest(other, candidate, leaderToken)).andExpect(status().isOk());
        Long othersParticipantId =
                eventParticipantRepository
                        .findByEventAndMember(other.getEvent(), candidate)
                        .orElseThrow()
                        .getId();

        mockMvc.perform(statusRequest(program, othersParticipantId, "CANCELLED", leaderToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_PARTICIPANT_NOT_FOUND"));
    }

    // ------------------------------------------------------------------ isEditable

    // 명단을 고칠 수 없는 사람에게는 isEditable이 거짓이다 — 명단 조회 자체는 인증만이다
    @Test
    void rosterIsNotEditableForOutsiders() throws Exception {
        addAndGetId(candidate);

        mockMvc.perform(authorized(get(membersPath(program)), outsiderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].isEditable").value(false));
    }

    // 종료된 활동에서는 스터디장에게도 거짓이다 — 저장이 409가 될 버튼을 켜지 않는다
    @Test
    void rosterIsNotEditableOnceCompleted() throws Exception {
        addAndGetId(candidate);
        transitionProgram(program, "APPROVE_COMPLETION");

        mockMvc.perform(authorized(get(membersPath(program)), leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].isEditable").value(false));
    }

    // ------------------------------------------------------------------ 이력

    /*
     * **세 경로가 모두 한 줄씩 남긴다** — 모집 선발(학술국장) · 행사 참가자 API(EVENT_MANAGE) · 팀원
     * 관리(스터디장). 한 경로만 적은 이력은 «누가 뺐나»에 답하지 못한다. 최신순이고, 처음 명단에 오른
     * 줄은 이전 상태가 없다.
     */
    @Test
    void everyPathThatChangesTheRosterLeavesOneHistoryRow() throws Exception {
        MemberEntity applicant = saveMember(UUID.randomUUID(), "신청한 회원");
        FormResponseHistoryEntity application =
                formResponseHistoryRepository.saveAndFlush(
                        FormResponseHistoryEntity.createSubmitted(
                                recruitmentForm,
                                applicant,
                                ResponseContent.of(Map.of("q1", "지원합니다")),
                                Instant.parse("2026-09-01T03:00:00Z")));

        // ① 모집 선발
        mockMvc.perform(
                        authorized(post(programPath(program) + "/recruitment/select"), managerToken)
                                .content(
                                        """
                                        {"selections": [{"formRspnsId": %d, "ptcpSttsCd": "CONFIRMED"}]}
                                        """
                                                .formatted(application.getId())))
                .andExpect(status().isOk());

        // ② 행사 참가자 API — 선발된 신청자를 대기로 내린다
        UUID eventManagerToken = UUID.randomUUID();
        grant(saveMember(eventManagerToken, "행사 운영자"), AuthorityCode.EVENT_MANAGE);
        Long applicantPtcpId = participantOf(applicant).getId();
        mockMvc.perform(
                        authorized(
                                        patch(
                                                "/v1/events/{eventId}/participants/{id}",
                                                program.getEvent().getId(),
                                                applicantPtcpId),
                                        eventManagerToken)
                                .content("{\"ptcpSttsCd\": \"WAITLISTED\"}"))
                .andExpect(status().isOk());

        // ③ 팀원 관리 — 신청서 없이 넣고 뺀다
        Long candidatePtcpId = addAndGetId(candidate);
        mockMvc.perform(statusRequest(program, candidatePtcpId, "CANCELLED", leaderToken))
                .andExpect(status().isOk());

        mockMvc.perform(authorized(get(membersPath(program) + "/history"), leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(4)))
                // 최신순 — 팀원 관리의 제외가 맨 위다
                .andExpect(jsonPath("$.data[0].mbrNm").value("늦게 합류하는 회원"))
                .andExpect(jsonPath("$.data[0].bfrPtcpSttsCd").value("CONFIRMED"))
                .andExpect(jsonPath("$.data[0].aftrPtcpSttsCd").value("CANCELLED"))
                .andExpect(jsonPath("$.data[0].chgPathSeCd").value("TEAM_MEMBERS"))
                .andExpect(jsonPath("$.data[0].prfmrNm").value("스터디장"))
                .andExpect(jsonPath("$.data[1].chgPathSeCd").value("TEAM_MEMBERS"))
                .andExpect(jsonPath("$.data[1].bfrPtcpSttsCd").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data[2].chgPathSeCd").value("EVENT_PARTICIPANTS"))
                .andExpect(jsonPath("$.data[2].aftrPtcpSttsCd").value("WAITLISTED"))
                .andExpect(jsonPath("$.data[2].prfmrNm").value("행사 운영자"))
                .andExpect(jsonPath("$.data[3].chgPathSeCd").value("RECRUITMENT_SELECTION"))
                .andExpect(jsonPath("$.data[3].bfrPtcpSttsCd").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data[3].aftrPtcpSttsCd").value("CONFIRMED"))
                .andExpect(jsonPath("$.data[3].mbrNm").value("신청한 회원"));
    }

    // 재합류도 한 줄이다 — 취소에서 확정으로
    @Test
    void rejoinLeavesAHistoryRowFromCancelledToConfirmed() throws Exception {
        Long eventPtcpId = addAndGetId(candidate);
        mockMvc.perform(statusRequest(program, eventPtcpId, "CANCELLED", leaderToken))
                .andExpect(status().isOk());
        mockMvc.perform(addRequest(program, candidate, leaderToken)).andExpect(status().isOk());

        entityManager.flush();
        List<EventParticipantStatusHistoryEntity> rows =
                historyRepository.findAllByParticipantEventOrderByIdDesc(program.getEvent());
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).getPreviousStatus()).isEqualTo(EventParticipantStatus.CANCELLED);
        assertThat(rows.get(0).getNextStatus()).isEqualTo(EventParticipantStatus.CONFIRMED);
        assertThat(rows.get(0).getChangePath()).isEqualTo(EventParticipantChangePath.TEAM_MEMBERS);
        assertThat(rows.get(0).getPerformer().getId()).isEqualTo(leader.getId());
    }

    // 누가 누구를 뺐는지는 팀원 전원이 볼 자리가 아니다 — 스터디장·학술국장만
    @Test
    void historyIsForbiddenForOutsiders() throws Exception {
        mockMvc.perform(authorized(get(membersPath(program) + "/history"), outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // ------------------------------------------------------------------ 출석과의 관계

    /*
     * 제외된 사람은 다음 회차 출석 대상(확정 팀원)에서 빠지고, **지난 출석은 남는다** — 제외가 행을
     * 지우지 않는 이유가 이것이다(atndc.event_ptcp_id가 그 행을 가리킨다).
     */
    @Test
    void excludedMemberLeavesFutureAttendanceButKeepsPastAttendance() throws Exception {
        Long eventPtcpId = addAndGetId(candidate);
        List<CurriculumItemEntity> items =
                curriculumItemRepository.findByAcademicProgramIdOrderBySeqnoAsc(program.getId());
        Long pastSessionId = submitSession(items.get(0), "2026-09-05", eventPtcpId, 201);

        mockMvc.perform(statusRequest(program, eventPtcpId, "CANCELLED", leaderToken))
                .andExpect(status().isOk());

        mockMvc.perform(
                        authorized(
                                get(programPath(program) + "/sessions/" + pastSessionId),
                                leaderToken))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath(
                                "$.data.attendances[*].eventPtcpId",
                                Matchers.contains(eventPtcpId.intValue())));
        mockMvc.perform(
                        authorized(get(membersPath(program)), leaderToken)
                                .param("ptcpSttsCd", "CONFIRMED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());

        // 다음 회차에 그 사람을 출석으로 올리면 확정 팀원이 아니라 400이다 (실패 요청이라 마지막)
        submitSession(items.get(1), "2026-09-12", eventPtcpId, 400);
    }

    // ------------------------------------------------------------------ 헬퍼

    private MockHttpServletRequestBuilder addRequest(
            AcademicProgramEntity target, MemberEntity member, UUID token) {
        return authorized(post(membersPath(target)), token)
                .content("{\"mbrId\": %d}".formatted(member.getId()));
    }

    private MockHttpServletRequestBuilder statusRequest(
            AcademicProgramEntity target, Long eventPtcpId, String nextStatus, UUID token) {
        return authorized(patch(membersPath(target) + "/" + eventPtcpId), token)
                .content("{\"ptcpSttsCd\": \"%s\"}".formatted(nextStatus));
    }

    private Long addAndGetId(MemberEntity member) throws Exception {
        String response =
                mockMvc.perform(addRequest(program, member, leaderToken))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.parse(response).read("$.data.eventPtcpId", Long.class);
    }

    private EventParticipantEntity participantOf(MemberEntity member) {
        entityManager.flush();
        return eventParticipantRepository
                .findByEventAndMember(program.getEvent(), member)
                .orElseThrow();
    }

    private Long submitSession(
            CurriculumItemEntity item, String realDate, Long attendeeId, int expectedStatus)
            throws Exception {
        String response =
                mockMvc.perform(
                                authorized(post(programPath(program) + "/sessions"), leaderToken)
                                        .content(
                                                """
                                                {"curriculumItemId": %d, "actlYmd": "%s",
                                                 "prgrsCn": "진행 내용",
                                                 "attendances": [{"eventPtcpId": %d, "atndYn": true}]}
                                                """
                                                        .formatted(
                                                                item.getId(),
                                                                realDate,
                                                                attendeeId)))
                        .andExpect(status().is(expectedStatus))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return expectedStatus == 201
                ? JsonPath.parse(response).read("$.data.sessionId", Long.class)
                : null;
    }

    private void transitionProgram(AcademicProgramEntity target, String transition)
            throws Exception {
        mockMvc.perform(
                        authorized(post(programPath(target) + "/transitions"), managerToken)
                                .content("{\"transition\": \"%s\"}".formatted(transition)))
                .andExpect(status().isOk());
    }

    private static String programPath(AcademicProgramEntity target) {
        return PROGRAMS + "/" + target.getId();
    }

    private static String membersPath(AcademicProgramEntity target) {
        return programPath(target) + "/members";
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
                List.of("OT", "1주차"));
    }

    // 문항이 하나 있는 DRAFT 모집 폼 — 승인 후속 처리의 빈 폼으로는 모집을 시작할 수 없다
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

    private MemberEntity saveMember(UUID authUserId, String name) {
        return saveMember(authUserId, name, MemberStatusCode.ENROLLED);
    }

    private MemberEntity saveMember(UUID authUserId, String name, MemberStatusCode statusCode) {
        String studentNumber = "2026612" + (studentNumberSeq++);
        return MemberFixture.save(
                memberRepository,
                memberGradeRepository,
                memberStatusRepository,
                authUserId,
                studentNumber,
                name,
                studentNumber + "@sscc.org",
                statusCode);
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
