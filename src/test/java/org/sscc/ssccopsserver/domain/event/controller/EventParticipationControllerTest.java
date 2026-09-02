package org.sscc.ssccopsserver.domain.event.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import org.sscc.ssccopsserver.domain.event.code.EventParticipantStatus;
import org.sscc.ssccopsserver.domain.event.code.EventStatusAction;
import org.sscc.ssccopsserver.domain.event.entity.EventClassificationEntity;
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
import org.sscc.ssccopsserver.support.AuthorityFixture;
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.MemberRoleFixture;

/*
 * 행사 신청 목록·참가자 명단 API(ssccops#146) 통합 검증.
 *
 * 표본(행사·폼·응답)은 리포지토리로 직접 만든다 — 확인하려는 것은 명단 규칙이지 그 표본을
 * 만드는 API가 아니고, 응답을 ACCEPTED로 만드는 일은 폼 검토 API(#141)의 몫이라 여기서
 * 호출하면 검사 대상이 두 배가 된다.
 *
 * **실패하는 요청은 언제나 성공하는 요청 뒤에 둔다.** 트랜잭션을 건 컨트롤러 테스트에서
 * 실패 요청은 참여 트랜잭션을 rollback-only로 표시하므로, 그 뒤에 오는 쓰기 요청은
 * UnexpectedRollbackException을 만난다 (AGENTS.md · RoleAuthoritySelfLockTest와 같은 이유).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EventParticipationControllerTest.StubJwtDecoderConfig.class)
@Transactional
class EventParticipationControllerTest {

    private static final String EVENTS = "/v1/events";

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

    private UUID managerToken;
    private UUID outsiderToken;
    private MemberEntity manager;
    private int studentNumberSeq = 1;

    @BeforeEach
    void setUp() {
        // 국장(OPERATOR)은 시드에서 EVENT_MANAGE의 상위를 갖는다 (EventControllerTest와 같은 픽스처)
        managerToken = UUID.randomUUID();
        manager = saveMember(managerToken, "행사운영자", MemberStatusCode.ENROLLED);
        MemberRoleFixture.assign(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                manager,
                MemberRoleFixture.DIRECTOR);

        // EVENT_MANAGE가 없는 회원. '다른 권한만' 가진 쪽이어야 403이 권한 때문이라는 것이 드러난다
        outsiderToken = UUID.randomUUID();
        MemberEntity outsider = saveMember(outsiderToken, "업무담당", MemberStatusCode.ENROLLED);
        AuthorityFixture.grant(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                authorityRepository,
                roleAuthorityRelationRepository,
                outsider,
                AuthorityCode.WORK_MANAGE);
    }

    /* ── 신청 목록 ────────────────────────────────────────── */

    /*
     * 신청 목록은 연결 폼의 응답 목록이다 — 폼 응답 목록 API와 같은 스키마이며 규칙을
     * 복제하지 않고 위임한다.
     */
    @Test
    void applicationsReturnLinkedFormResponses() throws Exception {
        FormEntity form = saveOpenForm();
        Long eventId = saveEvent("RECRUIT", "신청 받는 행사", form, null);
        saveResponse(form, saveMember("신청자1", MemberStatusCode.ENROLLED), false);
        saveResponse(form, saveMember("신청자2", MemberStatusCode.ENROLLED), true);

        mockMvc.perform(authorized(get(EVENTS + "/" + eventId + "/applications"), managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].member.mbrNm").exists());

        mockMvc.perform(
                        authorized(
                                get(EVENTS + "/" + eventId + "/applications?statusCode=ACCEPTED"),
                                managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    /*
     * 폼이 없는 행사는 빈 목록이 아니라 409다 — 빈 배열은 "아직 신청이 없다"로 읽히지만
     * 실제로는 신청을 받을 수단 자체가 없고 운영자가 할 일이 전혀 다르다.
     */
    @Test
    void applicationsOfEventWithoutFormReturn409() throws Exception {
        Long eventId = saveEvent("EVENT", "폼 없는 공지", null, null);

        mockMvc.perform(authorized(get(EVENTS + "/" + eventId + "/applications"), managerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_HAS_NO_FORM"));
    }

    /* ── 등록 ─────────────────────────────────────────────── */

    // 응답 기반 등록. 신청 근거(formRspnsId)가 남는 것이 수동 등록과 갈리는 지점이다
    @Test
    void registerFromAcceptedResponseKeepsApplicationSource() throws Exception {
        FormEntity form = saveOpenForm();
        Long eventId = saveEvent("RECRUIT", "신청 받는 행사", form, 20);
        MemberEntity applicant = saveMember("합격자", MemberStatusCode.ENROLLED);
        Long responseId = saveResponse(form, applicant, true);

        mockMvc.perform(
                        authorized(post(EVENTS + "/" + eventId + "/participants"), managerToken)
                                .content(
                                        """
                                        {"formRspnsId": %d, "ptcpSttsCd": "CONFIRMED"}
                                        """
                                                .formatted(responseId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.participant.ptcpSttsCd").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.participant.formRspnsId").value(responseId))
                .andExpect(jsonPath("$.data.participant.member.mbrId").value(applicant.getId()))
                .andExpect(jsonPath("$.data.confirmedCount").value(1))
                .andExpect(jsonPath("$.data.ptcpLmtCnt").value(20))
                .andExpect(jsonPath("$.data.capacityExceeded").value(false))
                .andExpect(jsonPath("$.data.warnings.length()").value(0));
    }

    // 수동 등록(전화·현장 접수). 폼을 거치지 않았으므로 근거가 없다
    @Test
    void registerManuallyLeavesApplicationSourceNull() throws Exception {
        Long eventId = saveEvent("EVENT", "폼 없는 행사", null, null);
        MemberEntity walkIn = saveMember("전화신청자", MemberStatusCode.ENROLLED);

        mockMvc.perform(
                        authorized(post(EVENTS + "/" + eventId + "/participants"), managerToken)
                                .content(
                                        """
                                        {"mbrId": %d, "ptcpSttsCd": "WAITLISTED"}
                                        """
                                                .formatted(walkIn.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.participant.ptcpSttsCd").value("WAITLISTED"))
                .andExpect(jsonPath("$.data.participant.formRspnsId").isEmpty())
                // 대기는 확정 인원에 들어가지 않는다 — 화면의 "N/정원"이 부풀지 않게 하는 자리다
                .andExpect(jsonPath("$.data.confirmedCount").value(0))
                .andExpect(jsonPath("$.data.ptcpLmtCnt").isEmpty())
                .andExpect(jsonPath("$.data.capacityExceeded").value(false));
    }

    /*
     * 근거는 정확히 하나여야 한다. 한쪽을 조용히 우선하면 form_rspns_id를 남길지 말지가
     * 추측으로 정해진다.
     */
    @Test
    void registerWithBothOrNeitherSourceReturns400() throws Exception {
        FormEntity form = saveOpenForm();
        Long eventId = saveEvent("RECRUIT", "신청 받는 행사", form, null);
        MemberEntity applicant = saveMember("합격자", MemberStatusCode.ENROLLED);
        Long responseId = saveResponse(form, applicant, true);

        assertRegisterRejected(
                eventId,
                """
                {"formRspnsId": %d, "mbrId": %d, "ptcpSttsCd": "CONFIRMED"}
                """
                        .formatted(responseId, applicant.getId()),
                "INVALID_PARTICIPANT_SOURCE");

        assertRegisterRejected(
                eventId,
                """
                {"ptcpSttsCd": "CONFIRMED"}
                """,
                "INVALID_PARTICIPANT_SOURCE");
    }

    // 취소로 시작하는 등록은 없다 — "참가자였던 적이 없는 취소자"를 만들지 않는다
    @Test
    void registerWithCancelledStatusReturns400() throws Exception {
        Long eventId = saveEvent("EVENT", "행사", null, null);
        MemberEntity walkIn = saveMember("전화신청자", MemberStatusCode.ENROLLED);

        assertRegisterRejected(
                eventId,
                """
                {"mbrId": %d, "ptcpSttsCd": "CANCELLED"}
                """
                        .formatted(walkIn.getId()),
                "INVALID_PARTICIPANT_REGISTRATION_STATUS");
    }

    /*
     * 심사와 등록은 순서가 있는 두 사건이다. 수락되지 않은 응답으로 명단에 올리면 폼의 심사
     * 결과와 참가자 명단이 서로 다른 사실을 말하게 된다.
     *
     * 다른 폼의 응답은 404다 — 범위 검사가 없으면 남의 행사 지원서를 근거로 사람을 올릴 수 있다.
     */
    @Test
    void registerFromUnacceptedOrForeignResponseIsRejected() throws Exception {
        FormEntity form = saveOpenForm();
        Long eventId = saveEvent("RECRUIT", "신청 받는 행사", form, null);
        Long submittedOnly =
                saveResponse(form, saveMember("심사 전", MemberStatusCode.ENROLLED), false);

        FormEntity otherForm = saveOpenForm();
        saveEvent("SEMINAR", "다른 행사", otherForm, null);
        Long foreignResponse =
                saveResponse(otherForm, saveMember("남의 신청자", MemberStatusCode.ENROLLED), true);

        assertRegisterRejected(
                eventId,
                """
                {"formRspnsId": %d, "ptcpSttsCd": "CONFIRMED"}
                """
                        .formatted(submittedOnly),
                "APPLICATION_NOT_ACCEPTED");

        assertRegisterRejected(
                eventId,
                """
                {"formRspnsId": %d, "ptcpSttsCd": "CONFIRMED"}
                """
                        .formatted(foreignResponse),
                "FORM_RESPONSE_NOT_FOUND");
    }

    // 같은 회원은 한 행사에 한 번이다 (uk_event_ptcp_event_member)
    @Test
    void duplicateRegistrationReturns409() throws Exception {
        Long eventId = saveEvent("EVENT", "행사", null, null);
        MemberEntity participant = saveMember("참가자", MemberStatusCode.ENROLLED);
        String body =
                """
                {"mbrId": %d, "ptcpSttsCd": "CONFIRMED"}
                """
                        .formatted(participant.getId());

        mockMvc.perform(
                        authorized(post(EVENTS + "/" + eventId + "/participants"), managerToken)
                                .content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(
                        authorized(post(EVENTS + "/" + eventId + "/participants"), managerToken)
                                .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_PARTICIPANT_DUPLICATED"));
    }

    /*
     * 정원 초과는 차단하지 않는다(D5 — 정원은 참고치다). 막는 대신 숫자를 실어 화면이 경고한다.
     */
    @Test
    void registrationBeyondCapacitySucceedsAndReportsExcess() throws Exception {
        Long eventId = saveEvent("EVENT", "정원 1명 행사", null, 1);

        registerConfirmed(eventId, saveMember("첫 참가자", MemberStatusCode.ENROLLED))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.confirmedCount").value(1))
                .andExpect(jsonPath("$.data.capacityExceeded").value(false));

        registerConfirmed(eventId, saveMember("정원 밖 참가자", MemberStatusCode.ENROLLED))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.confirmedCount").value(2))
                .andExpect(jsonPath("$.data.ptcpLmtCnt").value(1))
                .andExpect(jsonPath("$.data.capacityExceeded").value(true));
    }

    /*
     * 탈퇴·제명 회원도 막지 않는다(§8-5). 졸업생 홈커밍처럼 떠난 사람이 참가자인 것이 정상인
     * 행사가 있어 서버가 판단할 수 없다 — 대신 사람이 보게 한다.
     */
    @Test
    void registeringWithdrawnMemberSucceedsWithWarning() throws Exception {
        Long eventId = saveEvent("EVENT", "행사", null, null);

        registerConfirmed(eventId, saveMember("탈퇴 회원", MemberStatusCode.WITHDRAWN))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.warnings.length()").value(1))
                .andExpect(jsonPath("$.data.warnings[0].code").value("MEMBER_WITHDRAWN"));
    }

    /* ── 명단 조회 · 상태 전이 ────────────────────────────── */

    // 상태 미지정은 취소를 포함한 전부다 — 명단은 영구 보존이라 취소도 남는다(D16)
    @Test
    void participantListDefaultsToAllStatusesAndFilters() throws Exception {
        Long eventId = saveEvent("EVENT", "행사", null, null);
        saveParticipant(eventId, EventParticipantStatus.CONFIRMED);
        saveParticipant(eventId, EventParticipantStatus.WAITLISTED);
        saveParticipant(eventId, EventParticipantStatus.CANCELLED);

        mockMvc.perform(authorized(get(EVENTS + "/" + eventId + "/participants"), managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3));

        mockMvc.perform(
                        authorized(
                                get(EVENTS + "/" + eventId + "/participants?ptcpSttsCd=WAITLISTED"),
                                managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].ptcpSttsCd").value("WAITLISTED"));
    }

    /*
     * 허용 전이는 승격·강등·취소 셋이다. 승격이 정원을 넘겨도 막지 않고 숫자로 알리며, 강등은
     * 그 숫자를 다시 내린다. 마지막의 재취소는 400 — CANCELLED에서 나가는 길은 없다.
     *
     * 강등(CONFIRMED→WAITLISTED)은 #198에서 열었다. 그전까지 확정은 취소 말고는 나갈 곳이 없어
     * 잘못 확정한 사람을 대기로 되돌릴 방법이 없었다.
     */
    @Test
    void promotionDemotionAndCancellationFollowTheTransitionTable() throws Exception {
        Long eventId = saveEvent("EVENT", "정원 1명 행사", null, 1);
        Long waiting = saveParticipant(eventId, EventParticipantStatus.WAITLISTED);
        saveParticipant(eventId, EventParticipantStatus.CONFIRMED);

        changeStatus(eventId, waiting, "CONFIRMED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.participant.ptcpSttsCd").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.confirmedCount").value(2))
                .andExpect(jsonPath("$.data.capacityExceeded").value(true));

        changeStatus(eventId, waiting, "WAITLISTED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.participant.ptcpSttsCd").value("WAITLISTED"))
                .andExpect(jsonPath("$.data.confirmedCount").value(1))
                .andExpect(jsonPath("$.data.capacityExceeded").value(false));

        changeStatus(eventId, waiting, "CONFIRMED").andExpect(status().isOk());

        changeStatus(eventId, waiting, "CANCELLED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.participant.ptcpSttsCd").value("CANCELLED"))
                .andExpect(jsonPath("$.data.confirmedCount").value(1));

        changeStatus(eventId, waiting, "CONFIRMED")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARTICIPANT_STATUS_TRANSITION"));
    }

    // 표 밖의 전이. 같은 상태로의 재지정도 400이다 — 아무것도 바꾸지 않은 요청이 시점을 흐린다
    @Test
    void transitionsOutsideTheTableReturn400() throws Exception {
        Long eventId = saveEvent("EVENT", "행사", null, null);
        Long confirmed = saveParticipant(eventId, EventParticipantStatus.CONFIRMED);
        Long waiting = saveParticipant(eventId, EventParticipantStatus.WAITLISTED);

        assertTransitionRejected(eventId, confirmed, "CONFIRMED"); // 같은 상태로의 재지정
        assertTransitionRejected(eventId, waiting, "WAITLISTED"); // 같은 상태로의 재지정
        assertTransitionRejected(eventId, waiting, "CANCELLED"); // 대기자는 참가자였던 적이 없다
    }

    /* ── 범위·권한 ────────────────────────────────────────── */

    // 다른 행사의 참가자 식별자는 없는 참가자와 같은 404다
    @Test
    void participantOfAnotherEventReturns404() throws Exception {
        Long eventId = saveEvent("EVENT", "행사", null, null);
        Long otherEventId = saveEvent("SEMINAR", "다른 행사", null, null);
        Long foreignParticipant = saveParticipant(otherEventId, EventParticipantStatus.WAITLISTED);

        changeStatus(eventId, foreignParticipant, "CONFIRMED")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_PARTICIPANT_NOT_FOUND"));
    }

    // 조회도 EVENT_MANAGE를 요구한다 — 명단에는 신청자의 학번·학과가 실린다
    @Test
    void participantListRequiresEventManageAuthority() throws Exception {
        Long eventId = saveEvent("EVENT", "행사", null, null);

        mockMvc.perform(authorized(get(EVENTS + "/" + eventId + "/participants"), outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    /* ── 헬퍼 ─────────────────────────────────────────────── */

    private void assertRegisterRejected(Long eventId, String body, String expectedCode)
            throws Exception {
        mockMvc.perform(
                        authorized(post(EVENTS + "/" + eventId + "/participants"), managerToken)
                                .content(body))
                .andExpect(jsonPath("$.code").value(expectedCode));
    }

    private void assertTransitionRejected(Long eventId, Long participantId, String nextStatus)
            throws Exception {
        changeStatus(eventId, participantId, nextStatus)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARTICIPANT_STATUS_TRANSITION"));
    }

    private ResultActions registerConfirmed(Long eventId, MemberEntity member) throws Exception {
        return mockMvc.perform(
                authorized(post(EVENTS + "/" + eventId + "/participants"), managerToken)
                        .content(
                                """
                                {"mbrId": %d, "ptcpSttsCd": "CONFIRMED"}
                                """
                                        .formatted(member.getId())));
    }

    private ResultActions changeStatus(Long eventId, Long participantId, String nextStatus)
            throws Exception {
        return mockMvc.perform(
                authorized(
                                patch(EVENTS + "/" + eventId + "/participants/" + participantId),
                                managerToken)
                        .content(
                                """
                                {"ptcpSttsCd": "%s"}
                                """
                                        .formatted(nextStatus)));
    }

    /** 게시된 행사 하나. 폼과 정원은 선택이다 */
    private Long saveEvent(
            String classificationCode, String title, FormEntity form, Integer limitCount) {
        EventClassificationEntity classification =
                eventClassificationRepository.findById(classificationCode).orElseThrow();
        EventEntity event =
                EventEntity.create(
                        classification,
                        manager,
                        title,
                        "# 안내",
                        null,
                        form,
                        null,
                        null,
                        "학생회관",
                        limitCount);
        event.changeStatus(EventStatusAction.PUBLISH);
        return eventRepository.saveAndFlush(event).getId();
    }

    /** 접수 중인 폼. 신청(응답)의 그릇이다 */
    private FormEntity saveOpenForm() {
        QuestionCompositionContent composition =
                new QuestionCompositionContent(
                        List.of(new Page("기본 정보", null)),
                        List.of(
                                new QuestionItem(
                                        "q1",
                                        "이름",
                                        QuestionItemType.SHORT_TEXT,
                                        true,
                                        0,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null)));
        return formRepository.saveAndFlush(
                FormEntity.create(manager, "신청 폼", composition, null, null, FormStatus.OPEN));
    }

    /*
     * 제출된 응답 하나. accepted면 수락 상태까지 올린다 — 폼 검토 API(#141)를 부르지 않는 것은
     * 여기서 확인할 것이 명단 규칙이지 검토 규칙이 아니기 때문이다.
     */
    private Long saveResponse(FormEntity form, MemberEntity respondent, boolean accepted) {
        FormResponseHistoryEntity response =
                FormResponseHistoryEntity.createSubmitted(
                        form,
                        respondent,
                        ResponseContent.of(Map.of("q1", "홍길동")),
                        Instant.parse("2026-03-10T12:00:00Z"));
        if (accepted) {
            response.review(ResponseStatus.ACCEPTED);
        }
        return formResponseHistoryRepository.saveAndFlush(response).getId();
    }

    /*
     * 명단 표본 한 줄. 등록 팩토리는 취소 상태를 받지 않으므로(그것이 규칙이다) 취소 표본은
     * 확정으로 만든 뒤 전이시킨다 — 테스트를 위해 규칙에 예외를 두지 않는다.
     */
    private Long saveParticipant(Long eventId, EventParticipantStatus status) {
        EventEntity event = eventRepository.findById(eventId).orElseThrow();
        MemberEntity member = saveMember("참가자", MemberStatusCode.ENROLLED);
        boolean cancel = status == EventParticipantStatus.CANCELLED;
        EventParticipantEntity participant =
                EventParticipantEntity.register(
                        event,
                        member,
                        cancel ? EventParticipantStatus.CONFIRMED : status,
                        null,
                        manager);
        eventParticipantRepository.saveAndFlush(participant);
        if (cancel) {
            participant.changeStatus(EventParticipantStatus.CANCELLED);
            eventParticipantRepository.flush();
        }
        return participant.getId();
    }

    private MemberEntity saveMember(String name, MemberStatusCode statusCode) {
        return saveMember(UUID.randomUUID(), name, statusCode);
    }

    /*
     * 인증 주체가 될 회원은 토큰(= JWT의 sub)과 같은 authUserId로 만들어야 한다. 스텁 디코더가
     * 토큰 문자열을 그대로 sub로 쓰므로, 다른 UUID로 만들면 그 회원은 인증 컨버터에게 보이지
     * 않아 요청이 권한 부족이 아니라 403 SIGNUP_REQUIRED로 끊긴다.
     */
    private MemberEntity saveMember(UUID authUserId, String name, MemberStatusCode statusCode) {
        String studentNumber = "2026%04d".formatted(studentNumberSeq++);
        return MemberFixture.save(
                memberRepository,
                memberGradeRepository,
                memberStatusRepository,
                authUserId,
                studentNumber,
                name,
                studentNumber + "@soongsil.ac.kr",
                statusCode);
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
