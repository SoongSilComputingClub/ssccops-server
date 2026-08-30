package org.sscc.ssccopsserver.domain.academicprogram.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Arrays;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramStatus;
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
import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent.Page;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent.QuestionItem;
import org.sscc.ssccopsserver.domain.form.entity.ResponseContent;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormResponseHistoryRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormResponseReviewHistoryRepository;
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

/*
 * 팀원 명단·모집 신청자 조회·선발 API (#138).
 *
 * 세 엔드포인트의 자격이 전부 다른 것이 이 이슈의 핵심이라 토큰을 넷 둔다 — 학술국장(관리권한),
 * 스터디장(소유권), 국원(다른 권한만), 그리고 팀원. 특히 **스터디장이 선발을 호출하면 403**
 * 이어야 한다(2026-08-23 정정 — 선발 확정은 학술국장 전용이고 스터디장은 조회만 한다).
 *
 * 모집 시작(ONGOING)은 #133의 전이 API를 그대로 태워 만든다. 상태만 심으면 폼이 DRAFT로 남아
 * "모집 중인데 응답을 받지 않는 폼"이라는, 실제로는 만들어질 수 없는 상태로 나머지를 검증하게
 * 된다 — 전이가 폼 OPEN까지 함께 하는 것이 #133의 계약이다.
 *
 * 선발이 한 트랜잭션인지는 여기서 확인하지 않는다. 테스트에 @Transactional이 걸려 있으면
 * 롤백이 실제로 일어나지 않아 초록인 채 결함이 살아 있게 되므로, 그 규칙만
 * AcademicProgramRecruitmentRollbackTest가 트랜잭션 없이 확인한다(AGENTS.md).
 *
 * 실패를 기대하는 요청은 테스트마다 마지막에 한 번만 부른다 — 서비스가 @Transactional이라
 * 예외가 테스트 트랜잭션을 rollback-only로 표시하고, 그 뒤 이어지는 요청은
 * UnexpectedRollbackException을 만난다(AGENTS.md).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AcademicProgramRecruitmentControllerTest.StubJwtDecoderConfig.class)
@Transactional
class AcademicProgramRecruitmentControllerTest {

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
    @Autowired private FormResponseHistoryRepository formResponseHistoryRepository;
    @Autowired private FormResponseReviewHistoryRepository formResponseReviewHistoryRepository;
    @Autowired private AcademicProgramRepository academicProgramRepository;
    @Autowired private AcademicProgramTypeRepository academicProgramTypeRepository;
    @Autowired private CurriculumItemRepository curriculumItemRepository;

    private UUID managerToken;
    private MemberEntity manager;
    private UUID leaderToken;
    private MemberEntity leader;
    private UUID outsiderToken;
    private UUID teamMemberToken;

    private AcademicProgramEntity recruiting;
    private FormEntity recruitmentForm;
    private MemberEntity applicant;
    private MemberEntity anotherApplicant;

    @BeforeEach
    void setUp() throws Exception {
        managerToken = UUID.randomUUID();
        manager = saveMember(managerToken, "20260701", "학술국장");
        grant(manager, AuthorityCode.ACADEMIC_PROGRAM_MANAGE);

        leaderToken = UUID.randomUUID();
        leader = saveMember(leaderToken, "20260702", "스터디장");

        /*
         * ACADEMIC_PROGRAM_MANAGE가 없는 회원. 권한이 아예 없는 것이 아니라 '다른 권한만' 가져야
         * 인증만으로는 통과하지 못한다는 것이 드러난다(#133·#136 테스트와 같은 구성).
         */
        outsiderToken = UUID.randomUUID();
        grant(saveMember(outsiderToken, "20260703", "국원"), AuthorityCode.WORK_MANAGE);

        teamMemberToken = UUID.randomUUID();
        saveMember(teamMemberToken, "20260704", "팀원");

        applicant = saveMember(UUID.randomUUID(), "20260705", "지원자1");
        anotherApplicant = saveMember(UUID.randomUUID(), "20260706", "지원자2");

        recruiting = createProgram("알고리즘 스터디");
        recruitmentForm = linkForm(recruiting, "알고리즘 스터디");
        startRecruitment(recruiting);
    }

    // ------------------------------------------------------------------ 팀원 명단 (GET .../members)

    @Test
    void getMembersReturnsRosterWithLeaderFlag() throws Exception {
        register(recruiting, leader, EventParticipantStatus.CONFIRMED);
        EventParticipantEntity waiting =
                register(recruiting, applicant, EventParticipantStatus.WAITLISTED);

        mockMvc.perform(authorized(get(membersPath(recruiting)), teamMemberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(2)))
                // 정렬은 등록 순번(식별자 오름차순)이다
                .andExpect(jsonPath("$.data[0].mbrId").value(leader.getId()))
                .andExpect(jsonPath("$.data[0].mbrNm").value("스터디장"))
                .andExpect(jsonPath("$.data[0].ptcpSttsCd").value("CONFIRMED"))
                // isLeader는 명단이 아니라 acdm_actv.leadr_mbr_id와의 비교에서 온다
                .andExpect(jsonPath("$.data[0].isLeader").value(true))
                .andExpect(jsonPath("$.data[0].joinedAt").isNotEmpty())
                .andExpect(jsonPath("$.data[1].eventPtcpId").value(waiting.getId()))
                .andExpect(jsonPath("$.data[1].mbrNm").value("지원자1"))
                .andExpect(jsonPath("$.data[1].ptcpSttsCd").value("WAITLISTED"))
                .andExpect(jsonPath("$.data[1].isLeader").value(false))
                // 인증만으로 열리는 경로라 학번·학과·등급은 싣지 않는다
                .andExpect(jsonPath("$.data[0].member").doesNotExist())
                .andExpect(jsonPath("$.data[0].stdntNo").doesNotExist());
    }

    // 상태를 생략하면 취소도 함께 나온다 — 명단은 활동 이력으로 영구 보존한다
    @Test
    void getMembersWithoutFilterIncludesCancelled() throws Exception {
        register(recruiting, leader, EventParticipantStatus.CONFIRMED);
        cancel(register(recruiting, applicant, EventParticipantStatus.CONFIRMED));

        mockMvc.perform(authorized(get(membersPath(recruiting)), teamMemberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(2)))
                .andExpect(jsonPath("$.data[1].ptcpSttsCd").value("CANCELLED"));
    }

    @Test
    void getMembersWithStatusFilterNarrowsResult() throws Exception {
        register(recruiting, leader, EventParticipantStatus.CONFIRMED);
        register(recruiting, applicant, EventParticipantStatus.WAITLISTED);

        mockMvc.perform(
                        authorized(get(membersPath(recruiting)), teamMemberToken)
                                .param("ptcpSttsCd", "WAITLISTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data[0].mbrNm").value("지원자1"));
    }

    // 다른 활동의 팀원은 섞이지 않는다 — 질의가 경로의 활동이 가리키는 event만 읽는다
    @Test
    void getMembersExcludesOtherProgramsParticipants() throws Exception {
        register(recruiting, applicant, EventParticipantStatus.CONFIRMED);
        AcademicProgramEntity another = createProgram("남의 스터디");
        register(another, anotherApplicant, EventParticipantStatus.CONFIRMED);

        mockMvc.perform(authorized(get(membersPath(recruiting)), teamMemberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data[0].mbrNm").value("지원자1"));
    }

    /*
     * 모집 시작 전이어도 409가 아니라 빈 목록이다 — 아직 아무도 뽑지 않은 활동의 빈 명단은
     * 정상적인 답이고, 이 조회는 모집이 아니라 활동 상세 화면의 일부다(신청자 조회와 갈린다).
     */
    @Test
    void getMembersBeforeRecruitmentReturnsEmptyArray() throws Exception {
        AcademicProgramEntity approved = createProgram("아직 모집 전 스터디");

        mockMvc.perform(authorized(get(membersPath(approved)), teamMemberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void getMembersWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get(membersPath(recruiting))).andExpect(status().isUnauthorized());
    }

    @Test
    void getMembersOfUnknownProgramReturns404() throws Exception {
        mockMvc.perform(authorized(get(PROGRAMS + "/999999/members"), teamMemberToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACADEMIC_PROGRAM_NOT_FOUND"));
    }

    // ------------------------------------------------------------ 신청자 조회 (GET .../applications)

    @Test
    void getApplicationsAsLeaderReturnsSubmittedResponses() throws Exception {
        Long responseId = saveResponse(applicant, "지원 동기입니다");

        mockMvc.perform(authorized(get(applicationsPath(recruiting)), leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data[0].formRspnsId").value(responseId))
                .andExpect(jsonPath("$.data[0].rspnsSttsCd").value("SUBMITTED"))
                // 폼 응답 요약 DTO를 그대로 쓴다 — 신청자 표는 학번·학과까지 본다
                .andExpect(jsonPath("$.data[0].member.mbrNm").value("지원자1"));
    }

    // 국장도 감독 목적으로 본다 — 소유권과 관리권한의 OR이다
    @Test
    void getApplicationsAsManagerReturns200() throws Exception {
        saveResponse(applicant, "지원 동기입니다");

        mockMvc.perform(authorized(get(applicationsPath(recruiting)), managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)));
    }

    // 기본값은 '전체'가 아니라 'DRAFT를 뺀 전부'다 — 폼 응답 목록의 규칙을 그대로 위임한다
    @Test
    void getApplicationsExcludesDraftByDefault() throws Exception {
        saveResponse(applicant, "제출한 지원서");
        saveDraft(anotherApplicant);

        mockMvc.perform(authorized(get(applicationsPath(recruiting)), leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data[0].member.mbrNm").value("지원자1"));

        mockMvc.perform(
                        authorized(get(applicationsPath(recruiting)), leaderToken)
                                .param("statusCode", "DRAFT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data[0].member.mbrNm").value("지원자2"));
    }

    /*
     * 신청자 목록은 참가 상태를 함께 싣는다 (#198).
     *
     * 선발이 심사와 등록을 함께 하므로 확정이든 대기든 응답은 똑같이 ACCEPTED가 된다 — 응답
     * 상태만 보고 그리면 대기로 뽑은 신청자가 확정자와 똑같이 '선발 완료'로 표시된다
     * (ssccops-web#209). **아직 선발되지 않은 신청자는 두 값 모두 null**이며 서버가 "미선발"
     * 같은 대체값을 만들지 않는다.
     */
    @Test
    void getApplicationsCarryParticipantStatus() throws Exception {
        Long waitlisted = saveResponse(applicant, "지원 동기입니다");
        saveResponse(anotherApplicant, "저도 지원합니다");
        select("%d:WAITLISTED".formatted(waitlisted));

        Long eventPtcpId =
                eventParticipantRepository
                        .findAllByEventAndStatusInOrderByIdAsc(
                                recruiting.getEvent(), List.of(EventParticipantStatus.WAITLISTED))
                        .get(0)
                        .getId();

        // 제출 일시가 같으면 식별자 내림차순이라 나중에 낸 지원자2가 앞이다
        mockMvc.perform(authorized(get(applicationsPath(recruiting)), leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(2)))
                .andExpect(jsonPath("$.data[0].member.mbrNm").value("지원자2"))
                .andExpect(jsonPath("$.data[0].eventPtcpId").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data[0].ptcpSttsCd").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data[1].member.mbrNm").value("지원자1"))
                .andExpect(jsonPath("$.data[1].eventPtcpId").value(eventPtcpId))
                .andExpect(jsonPath("$.data[1].ptcpSttsCd").value("WAITLISTED"))
                // 폼 응답 요약의 값은 그대로 실린다 — 참가 상태만 얹은 모양이다
                .andExpect(jsonPath("$.data[1].formRspnsId").value(waitlisted))
                .andExpect(jsonPath("$.data[1].rspnsSttsCd").value("ACCEPTED"))
                .andExpect(jsonPath("$.data[1].rspnsSeq").value(1));
    }

    // 취소된 참가자도 그대로 CANCELLED다 — 명단은 활동 이력으로 영구 보존한다(D16)
    @Test
    void getApplicationsCarryCancelledParticipantStatus() throws Exception {
        Long responseId = saveResponse(applicant, "지원 동기입니다");
        select("%d:CONFIRMED".formatted(responseId));
        cancel(participantOf(EventParticipantStatus.CONFIRMED));

        mockMvc.perform(authorized(get(applicationsPath(recruiting)), leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].ptcpSttsCd").value("CANCELLED"));
    }

    // 스터디장도 국장도 아니면 403이다 — 신청자 명부는 인증만으로 열리지 않는다
    @Test
    void getApplicationsAsOutsiderReturns403() throws Exception {
        saveResponse(applicant, "지원 동기입니다");

        mockMvc.perform(authorized(get(applicationsPath(recruiting)), outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    /*
     * 모집 전에는 빈 목록이 아니라 409다 — 빈 배열은 "아무도 지원하지 않았다"로 읽히는데
     * 실제로는 신청을 받는 창구가 아직 열리지 않았다.
     */
    @Test
    void getApplicationsBeforeRecruitmentReturns409() throws Exception {
        AcademicProgramEntity approved = createProgram("아직 모집 전 스터디");
        linkForm(approved, "아직 모집 전 스터디");

        mockMvc.perform(authorized(get(applicationsPath(approved)), leaderToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECRUITMENT_NOT_STARTED"));
    }

    // ------------------------------------------------------------------ 선발 (POST .../select)

    @Test
    void selectMembersAcceptsResponsesAndRegistersParticipants() throws Exception {
        Long confirmedResponse = saveResponse(applicant, "지원 동기입니다");
        Long waitlistedResponse = saveResponse(anotherApplicant, "저도 지원합니다");

        mockMvc.perform(
                        authorized(post(selectPath(recruiting)), managerToken)
                                .content(
                                        selectBody(
                                                "%d:CONFIRMED".formatted(confirmedResponse),
                                                "%d:WAITLISTED".formatted(waitlistedResponse))))
                .andExpect(status().isOk())
                // 응답은 고른 줄이 아니라 갱신된 팀원 명단 전체다
                .andExpect(jsonPath("$.data", Matchers.hasSize(2)))
                .andExpect(jsonPath("$.data[0].mbrNm").value("지원자1"))
                .andExpect(jsonPath("$.data[0].ptcpSttsCd").value("CONFIRMED"))
                .andExpect(jsonPath("$.data[0].isLeader").value(false))
                .andExpect(jsonPath("$.data[1].mbrNm").value("지원자2"))
                .andExpect(jsonPath("$.data[1].ptcpSttsCd").value("WAITLISTED"));

        // 심사와 등록은 한 건이다 — 폼 응답도 함께 수락 상태로 옮겨져 있어야 한다
        assertThat(
                        formResponseHistoryRepository
                                .findById(confirmedResponse)
                                .orElseThrow()
                                .getStatus())
                .isEqualTo(ResponseStatus.ACCEPTED);
        assertThat(
                        formResponseHistoryRepository
                                .findById(waitlistedResponse)
                                .orElseThrow()
                                .getStatus())
                .isEqualTo(ResponseStatus.ACCEPTED);

        // 명단 행은 신청 근거(form_rspns_id)를 달고 있어야 한다 — 수동 등록과 구별되는 값이다
        assertThat(
                        eventParticipantRepository
                                .findAllByEventAndStatusInOrderByIdAsc(
                                        recruiting.getEvent(),
                                        List.of(EventParticipantStatus.CONFIRMED))
                                .get(0)
                                .getFormResponse()
                                .getId())
                .isEqualTo(confirmedResponse);
    }

    /*
     * 정원은 참고치다(설계 결정 #2) — 초과해도 차단하지 않는다. 막으면 한 명을 더 받으려고
     * 활동 정보를 먼저 고쳐야 하는 절차가 생긴다.
     */
    @Test
    void selectMembersOverCapacityStillSucceeds() throws Exception {
        setCapacityMaxCount(recruiting, 1);
        Long first = saveResponse(applicant, "지원 동기입니다");
        Long second = saveResponse(anotherApplicant, "저도 지원합니다");

        mockMvc.perform(
                        authorized(post(selectPath(recruiting)), managerToken)
                                .content(
                                        selectBody(
                                                "%d:CONFIRMED".formatted(first),
                                                "%d:CONFIRMED".formatted(second))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(2)))
                .andExpect(jsonPath("$.data[0].ptcpSttsCd").value("CONFIRMED"))
                .andExpect(jsonPath("$.data[1].ptcpSttsCd").value("CONFIRMED"));
    }

    /*
     * **선발 확정은 학술국장 전용이다**(2026-08-23 정정). 스터디장은 신청자 목록을 볼 수는
     * 있지만 선발에는 관여하지 않는다 — 조회가 통과한다고 선발도 통과하면 정정이 무의미해진다.
     */
    @Test
    void selectMembersAsLeaderReturns403() throws Exception {
        Long responseId = saveResponse(applicant, "지원 동기입니다");

        mockMvc.perform(
                        authorized(post(selectPath(recruiting)), leaderToken)
                                .content(selectBody("%d:CONFIRMED".formatted(responseId))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void selectMembersAsOutsiderReturns403() throws Exception {
        Long responseId = saveResponse(applicant, "지원 동기입니다");

        mockMvc.perform(
                        authorized(post(selectPath(recruiting)), outsiderToken)
                                .content(selectBody("%d:CONFIRMED".formatted(responseId))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void selectMembersBeforeRecruitmentReturns409() throws Exception {
        AcademicProgramEntity approved = createProgram("아직 모집 전 스터디");
        linkForm(approved, "아직 모집 전 스터디");

        mockMvc.perform(
                        authorized(post(selectPath(approved)), managerToken)
                                .content(selectBody("1:CONFIRMED")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECRUITMENT_NOT_STARTED"));
    }

    // 취소로 시작하는 등록은 없다 — 판정은 EventParticipantStatus.isRegistrable이 갖는다
    @Test
    void selectMembersWithCancelledStatusReturns400() throws Exception {
        Long responseId = saveResponse(applicant, "지원 동기입니다");

        mockMvc.perform(
                        authorized(post(selectPath(recruiting)), managerToken)
                                .content(selectBody("%d:CANCELLED".formatted(responseId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARTICIPANT_REGISTRATION_STATUS"));
    }

    // 아무도 고르지 않은 확정은 하려는 일이 없는 요청이다
    @Test
    void selectMembersWithEmptySelectionsReturns400() throws Exception {
        mockMvc.perform(
                        authorized(post(selectPath(recruiting)), managerToken)
                                .content("{\"selections\": []}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // 다른 폼의 응답 식별자는 없는 응답과 같은 404다 — 남의 신청서로 팀원을 만들 수 없다
    @Test
    void selectMembersWithForeignResponseReturns404() throws Exception {
        AcademicProgramEntity another = createProgram("남의 스터디");
        FormEntity anotherForm = linkForm(another, "남의 스터디");
        Long foreignResponse = saveResponse(anotherForm, anotherApplicant, "남의 지원서");

        mockMvc.perform(
                        authorized(post(selectPath(recruiting)), managerToken)
                                .content(selectBody("%d:CONFIRMED".formatted(foreignResponse))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FORM_RESPONSE_NOT_FOUND"));
    }

    /*
     * 같은 값으로 다시 저장하면 아무것도 바뀌지 않는다 (#198).
     *
     * 웹이 "지금 화면의 상태를 그대로 보낸다"는 한 가지 모델만 쓸 수 있어야 하므로, 바뀌지 않은
     * 줄이 함께 실려 오는 것이 정상이다 — 그전까지는 폼 응답이 종결이라 400, 명단 행이 이미
     * 있으면 409였다. **검토 이력이 늘지 않는 것**까지가 이 규칙이다: 재선발은 재심사가 아니라
     * 참가 상태를 고치는 일이고, 통과시키면 아무것도 바꾸지 않은 승인이 타임라인에 쌓인다.
     */
    @Test
    void selectMembersAgainWithSameStatusChangesNothing() throws Exception {
        Long responseId = saveResponse(applicant, "지원 동기입니다");
        selectConfirmed(responseId);
        Long eventPtcpId = participantOf(EventParticipantStatus.CONFIRMED).getId();

        selectConfirmed(responseId);

        mockMvc.perform(authorized(get(membersPath(recruiting)), teamMemberToken))
                .andExpect(status().isOk())
                // 행이 하나 더 생기지도, 상태가 바뀌지도 않는다
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data[0].eventPtcpId").value(eventPtcpId))
                .andExpect(jsonPath("$.data[0].ptcpSttsCd").value("CONFIRMED"));

        FormResponseHistoryEntity response =
                formResponseHistoryRepository.findById(responseId).orElseThrow();
        assertThat(response.getStatus()).isEqualTo(ResponseStatus.ACCEPTED);
        assertThat(
                        formResponseReviewHistoryRepository
                                .findAllByResponseOrderByProcessedAtAscIdAsc(response))
                .hasSize(1);
    }

    /*
     * 확정과 대기 사이는 양방향이다 (#198). 대기로 뒀다가 확정으로 올리고 다시 내리는 것이
     * 모집 운영의 정상 흐름이라, 그 왕복에 방향이 하나만 있을 이유가 없다. 같은 행이 움직일 뿐
     * 새 행이 생기지 않는다 — 명단의 열쇠는 (행사, 회원)이다.
     */
    @Test
    void selectMembersMovesBetweenConfirmedAndWaitlisted() throws Exception {
        Long responseId = saveResponse(applicant, "지원 동기입니다");
        selectConfirmed(responseId);
        Long eventPtcpId = participantOf(EventParticipantStatus.CONFIRMED).getId();

        // 강등 — 그전까지 400 INVALID_PARTICIPANT_STATUS_TRANSITION이던 자리다
        select("%d:WAITLISTED".formatted(responseId))
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data[0].eventPtcpId").value(eventPtcpId))
                .andExpect(jsonPath("$.data[0].ptcpSttsCd").value("WAITLISTED"));

        // 다시 승격
        select("%d:CONFIRMED".formatted(responseId))
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data[0].eventPtcpId").value(eventPtcpId))
                .andExpect(jsonPath("$.data[0].ptcpSttsCd").value("CONFIRMED"));

        // 참가 상태만 오갔을 뿐 심사 결과는 그대로다 — 신청을 냈고 승인됐다는 사실은 변하지 않는다
        assertThat(formResponseHistoryRepository.findById(responseId).orElseThrow().getStatus())
                .isEqualTo(ResponseStatus.ACCEPTED);
    }

    /*
     * 이미 뽑은 신청자에게도 취소는 고를 수 없다 (#198). 명단 행이 있으면 전이표가
     * CONFIRMED→CANCELLED를 허용하므로, 이 검사가 없으면 선발 저장이 '이미 뽑힌 사람'에 한해
     * 취소 API가 된다 — 선발이 고르는 값은 확정과 대기뿐이고 취소는 명단 화면의 조작이다.
     */
    @Test
    void selectMembersWithCancelledStatusReturns400EvenWhenAlreadySelected() throws Exception {
        Long responseId = saveResponse(applicant, "지원 동기입니다");
        selectConfirmed(responseId);

        mockMvc.perform(
                        authorized(post(selectPath(recruiting)), managerToken)
                                .content(selectBody("%d:CANCELLED".formatted(responseId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARTICIPANT_REGISTRATION_STATUS"));
    }

    /*
     * 취소를 되돌리는 것은 이 이슈의 범위 밖이다 — 취소에서 나가는 길이 없다는 전이표가 선발
     * 경로에도 그대로 걸린다(판정을 서비스에 옮겨 적지 않았다는 뜻이기도 하다).
     */
    @Test
    void selectMembersOfCancelledParticipantReturns400() throws Exception {
        Long responseId = saveResponse(applicant, "지원 동기입니다");
        selectConfirmed(responseId);
        cancel(participantOf(EventParticipantStatus.CONFIRMED));

        mockMvc.perform(
                        authorized(post(selectPath(recruiting)), managerToken)
                                .content(selectBody("%d:CONFIRMED".formatted(responseId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARTICIPANT_STATUS_TRANSITION"));
    }

    /*
     * 승인으로 갈 수 없는 응답은 종전대로 끊긴다. 재선발이 열렸다고 심사 전이표가 느슨해진 것이
     * 아니다 — 이미 승인된 응답만 검토를 건너뛴다.
     */
    @Test
    void selectMembersWithDraftResponseReturns400() throws Exception {
        Long draftId = saveDraft(anotherApplicant);

        mockMvc.perform(
                        authorized(post(selectPath(recruiting)), managerToken)
                                .content(selectBody("%d:CONFIRMED".formatted(draftId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RESPONSE_STATUS_TRANSITION"));
    }

    // ------------------------------------------------------------------ 헬퍼

    private void selectConfirmed(Long formResponseId) throws Exception {
        select("%d:CONFIRMED".formatted(formResponseId));
    }

    /** 성공을 기대하는 선발 요청. 이어지는 검증은 갱신된 팀원 명단(응답 본문)에 건다 */
    private ResultActions select(String... selections) throws Exception {
        return mockMvc.perform(
                        authorized(post(selectPath(recruiting)), managerToken)
                                .content(selectBody(selections)))
                .andExpect(status().isOk());
    }

    private EventParticipantEntity participantOf(EventParticipantStatus status) {
        return eventParticipantRepository
                .findAllByEventAndStatusInOrderByIdAsc(recruiting.getEvent(), List.of(status))
                .get(0);
    }

    /** "응답식별자:상태" 꼴을 selections 본문으로 옮긴다 */
    private static String selectBody(String... selections) {
        String rows =
                Arrays.stream(selections)
                        .map(selection -> selection.split(":"))
                        .map(
                                parts ->
                                        """
                                        {"formRspnsId": %s, "ptcpSttsCd": "%s"}
                                        """
                                                .formatted(parts[0], parts[1]))
                        .reduce((left, right) -> left + "," + right)
                        .orElse("");
        return "{\"selections\": [%s]}".formatted(rows);
    }

    private String membersPath(AcademicProgramEntity program) {
        return PROGRAMS + "/" + program.getId() + "/members";
    }

    private String applicationsPath(AcademicProgramEntity program) {
        return PROGRAMS + "/" + program.getId() + "/recruitment/applications";
    }

    private String selectPath(AcademicProgramEntity program) {
        return PROGRAMS + "/" + program.getId() + "/recruitment/select";
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
                List.of("OT"));
    }

    /*
     * 모집 폼을 만들어 활동의 Event에 연결한다. 승인 후속 처리(#133)가 생성 시점에 하는 일을
     * 흉내 내는 것이라 상태는 DRAFT다 — 여는 것은 모집 시작 전이의 몫이다.
     */
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
                                leader,
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

    /** #133의 전이 API를 그대로 태운다 — 상태만 심으면 폼이 DRAFT로 남는다 */
    private void startRecruitment(AcademicProgramEntity program) throws Exception {
        mockMvc.perform(
                        authorized(
                                        post(PROGRAMS + "/" + program.getId() + "/transitions"),
                                        managerToken)
                                .content("{\"transition\": \"START_RECRUITMENT\"}"))
                .andExpect(status().isOk());
        assertThat(program.getStatus()).isEqualTo(AcademicProgramStatus.ONGOING);
    }

    /*
     * 정원은 픽스처가 채우지 않으므로(승인 이관 #150의 몫) 벌크 UPDATE로 심는다 — 정원 초과가
     * 차단되지 않는다는 것만 확인하면 되고, 그 값이 어디서 왔는지는 이 이슈의 규칙이 아니다.
     */
    private void setCapacityMaxCount(AcademicProgramEntity program, int capacity) {
        entityManager.flush();
        entityManager
                .createQuery(
                        "update AcademicProgramEntity p set p.capacityMaxCount = :capacity"
                                + " where p.id = :id")
                .setParameter("capacity", capacity)
                .setParameter("id", program.getId())
                .executeUpdate();
        entityManager.refresh(program);
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
                                Instant.parse("2026-09-01T03:00:00Z")))
                .getId();
    }

    private Long saveDraft(MemberEntity respondent) {
        return formResponseHistoryRepository
                .saveAndFlush(
                        FormResponseHistoryEntity.createDraft(
                                recruitmentForm,
                                respondent,
                                ResponseContent.of(Map.of("q1", "작성 중"))))
                .getId();
    }

    private EventParticipantEntity register(
            AcademicProgramEntity program, MemberEntity member, EventParticipantStatus status) {
        return eventParticipantRepository.saveAndFlush(
                EventParticipantEntity.register(program.getEvent(), member, status, null, manager));
    }

    private void cancel(EventParticipantEntity participant) {
        participant.changeStatus(EventParticipantStatus.CANCELLED);
        eventParticipantRepository.flush();
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
