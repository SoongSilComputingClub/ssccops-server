package org.sscc.ssccopsserver.domain.operation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleClassificationRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkCreateResponse;
import org.sscc.ssccopsserver.domain.operation.dto.WorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.WorkCreateResponse;
import org.sscc.ssccopsserver.domain.operation.entity.WorkType;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkTypeRepository;
import org.sscc.ssccopsserver.domain.operation.service.SubWorkService;
import org.sscc.ssccopsserver.domain.operation.service.WorkService;
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.MemberRoleFixture;
import org.sscc.ssccopsserver.support.SubWorkTypeFixture;
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

import com.jayway.jsonpath.JsonPath;

/*
 * 회의 API (OPS-024~029, #83). JwtDecoder를 대체해 필터체인 전체를 태우는 방식은
 * WorkControllerTest·SubWorkControllerTest와 같다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestJwtDecoderConfig.class, MeetingControllerTest.FixedClockConfig.class})
@Transactional
class MeetingControllerTest {

    private static final UUID AUTH_USER_ID = UUID.randomUUID();

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    // 전이 일시 검증용 고정 시각. 서비스가 주입된 Clock을 쓰지 않으면 이 값과 어긋난다 (#117)
    private static final OffsetDateTime TRANSITION_NOW =
            OffsetDateTime.of(2026, 9, 3, 19, 30, 0, 0, KST);

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private WorkService workService;
    @Autowired private SubWorkService subWorkService;
    @Autowired private SubWorkTypeRepository subWorkTypeRepository;

    private Long otherMemberId;
    private Long registrantId;
    private MemberEntity registrant;
    private Long linkedOperationId;
    private Long linkedWorkId;

    @BeforeEach
    void setUp() {
        otherMemberId = saveMember(UUID.randomUUID(), "20200001", "김도현", "owner@sscc.org").getId();
        // 토큰의 sub(AUTH_USER_ID)와 연결된 회원. 회의 책임자로도 쓰여 전이 권한 테스트가 이 회원을 의장으로 삼는다
        registrant = saveMember(AUTH_USER_ID, "20200002", "이서연", "actor@sscc.org");
        registrantId = registrant.getId();

        // 회의 API는 MEETING_MANAGE를 요구한다(#9 준용). 국장이 OPERATOR를 통해 닿는다
        MemberRoleFixture.assign(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                registrant,
                MemberRoleFixture.DIRECTOR);

        // 안건이 연결할 운영 건(업무) 하나
        WorkCreateResponse linkedWork =
                workService.createWork(
                        new WorkCreateRequest(
                                "2026 동아리 박람회",
                                WorkType.EVENT,
                                otherMemberId,
                                null,
                                null,
                                null,
                                null),
                        registrant);
        linkedOperationId = linkedWork.operationId();
        linkedWorkId = linkedWork.workId();
    }

    // ------------------------------------------------------------------ 등록

    @Test
    void createMeetingReturns201WithLocationAndAgendas() throws Exception {
        String body =
                """
                {
                  "title": "9월 1차 정기회의",
                  "meetingCategory": "REGULAR",
                  "personInChargeId": %d,
                  "startAt": "2026-09-03T19:00:00+09:00",
                  "endAt": "2026-09-03T21:00:00+09:00",
                  "priority": "NORMAL",
                  "attendeeScope": "ALL",
                  "location": "동아리방",
                  "agendas": [
                    {"targetOperationId": %d, "processStatus": "PENDING", "content": "박람회 부스 배치"},
                    {"targetOperationId": %d, "processStatus": "PENDING"}
                  ]
                }
                """
                        .formatted(otherMemberId, linkedOperationId, linkedOperationId);

        mockMvc.perform(authenticated(post("/v1/meetings"), body))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.meetingId").isNumber())
                .andExpect(jsonPath("$.data.meetingCategory").value("REGULAR"))
                .andExpect(jsonPath("$.data.meetingStatus").value("SCHEDULED"))
                .andExpect(jsonPath("$.data.personInCharge.memberId").value(otherMemberId))
                .andExpect(jsonPath("$.data.location").value("동아리방"))
                .andExpect(jsonPath("$.data.agendas.length()").value(2))
                .andExpect(jsonPath("$.data.agendas[0].agendaOrder").value(1))
                .andExpect(
                        jsonPath("$.data.agendas[0].targetOperation.operationId")
                                .value(linkedOperationId))
                .andExpect(jsonPath("$.data.agendas[0].processStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.agendas[1].agendaOrder").value(2))
                .andExpect(
                        jsonPath("$.data.agendas[1].targetOperation.operationId")
                                .value(linkedOperationId));
    }

    @Test
    void missingRequiredFieldReturnsValidationFailed() throws Exception {
        String body =
                """
                {
                  "meetingCategory": "REGULAR",
                  "personInChargeId": %d
                }
                """
                        .formatted(otherMemberId);

        mockMvc.perform(authenticated(post("/v1/meetings"), body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void unknownMeetingCategoryReturnsInvalidCodeValue() throws Exception {
        String body =
                """
                {
                  "title": "코드값 밖 회의",
                  "meetingCategory": "ANNUAL",
                  "personInChargeId": %d,
                  "startAt": "2026-09-03T19:00:00+09:00"
                }
                """
                        .formatted(otherMemberId);

        mockMvc.perform(authenticated(post("/v1/meetings"), body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE_VALUE"));
    }

    @Test
    void invertedPeriodReturnsValidationFailed() throws Exception {
        String body =
                """
                {
                  "title": "기간 역전 회의",
                  "meetingCategory": "REGULAR",
                  "personInChargeId": %d,
                  "startAt": "2026-09-03T21:00:00+09:00",
                  "endAt": "2026-09-03T19:00:00+09:00"
                }
                """
                        .formatted(otherMemberId);

        mockMvc.perform(authenticated(post("/v1/meetings"), body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void unknownPersonInChargeReturnsValidationFailed() throws Exception {
        String body =
                """
                {
                  "title": "담당자 없는 회의",
                  "meetingCategory": "REGULAR",
                  "personInChargeId": %d,
                  "startAt": "2026-09-03T19:00:00+09:00"
                }
                """
                        .formatted(otherMemberId + 999);

        mockMvc.perform(authenticated(post("/v1/meetings"), body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /*
     * 안건은 운영 건 또는 제목 중 정확히 하나를 갖는다 (#625 · ADR-0059). ADR-0055 시절에는 이
     * 자리가 「운영 건을 안 주면 400」이었고, 드래프트가 돌아온 지금은 「둘 다 주면 400」이다.
     */
    @Test
    void agendaWithBothTargetAndNameReturnsValidationFailed() throws Exception {
        String body =
                """
                {
                  "title": "안건에 운영 건과 제목이 둘 다 있다",
                  "meetingCategory": "TOPIC",
                  "personInChargeId": %d,
                  "startAt": "2026-09-03T19:00:00+09:00",
                  "agendas": [{"targetOperationId": %d, "agendaName": "따로 붙인 제목"}]
                }
                """
                        .formatted(otherMemberId, linkedOperationId);

        mockMvc.perform(authenticated(post("/v1/meetings"), body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void agendaWithNeitherTargetNorNameReturnsValidationFailed() throws Exception {
        String body =
                """
                {
                  "title": "안건 아무것도 없음",
                  "meetingCategory": "TOPIC",
                  "personInChargeId": %d,
                  "startAt": "2026-09-03T19:00:00+09:00",
                  "agendas": [{"content": "본문만 있음"}]
                }
                """
                        .formatted(otherMemberId);

        mockMvc.perform(authenticated(post("/v1/meetings"), body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void createMeetingWithoutTokenReturns401() throws Exception {
        mockMvc.perform(post("/v1/meetings").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // 운영진이 아닌 회원(스터디장)은 권한이 아예 없어 인가 단계에서 막힌다
    @Test
    void createMeetingWithoutMeetingManageReturns403() throws Exception {
        UUID outsiderToken = UUID.randomUUID();
        MemberEntity outsider = saveMember(outsiderToken, "20200003", "박현우", "outsider@sscc.org");
        MemberRoleFixture.assign(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                outsider,
                MemberRoleFixture.STUDY_LEADER);

        // 이 테스트만 별도 토큰이 필요하므로 헤더를 직접 채운다
        String body =
                """
                {
                  "title": "권한 없는 등록 시도",
                  "meetingCategory": "REGULAR",
                  "personInChargeId": %d,
                  "startAt": "2026-09-03T19:00:00+09:00"
                }
                """
                        .formatted(otherMemberId);

        mockMvc.perform(
                        post("/v1/meetings")
                                .header("Authorization", "Bearer " + outsiderToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // ------------------------------------------------------------------ 조회

    @Test
    void getMeetingReturns200WithDetail() throws Exception {
        Long meetingId = createMeeting(otherMemberId);

        mockMvc.perform(authenticated(get("/v1/meetings/{meetingId}", meetingId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.meetingId").value(meetingId))
                .andExpect(jsonPath("$.data.meetingStatus").value("SCHEDULED"))
                .andExpect(jsonPath("$.data.agendas").isArray());
    }

    @Test
    void getUnknownMeetingReturns404() throws Exception {
        mockMvc.perform(authenticated(get("/v1/meetings/{meetingId}", 999_999L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void listMeetingsReturns200WithAgendaCount() throws Exception {
        Long meetingId = createMeetingWithOneLinkedAgenda(otherMemberId);

        mockMvc.perform(authenticated(get("/v1/meetings")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].meetingId").value(meetingId))
                .andExpect(jsonPath("$.data[0].agendaCount").value(1))
                .andExpect(jsonPath("$.page").doesNotExist());
    }

    // ------------------------------------------------------------------ 상태 전이

    @Test
    void transitionOpenByChairSucceeds() throws Exception {
        Long meetingId = createMeeting(registrantId); // 토큰 주체(registrant) 본인이 의장이다

        mockMvc.perform(transition(meetingId, "OPEN", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.previousMeetingStatus").value("SCHEDULED"))
                .andExpect(jsonPath("$.data.meetingStatus").value("IN_PROGRESS"));
    }

    /*
     * 전이 일시는 Instant.now()가 아니라 주입된 Clock에서 온다 (#117). 직접 호출하던 동안에는
     * 이 값이 매 실행마다 달라 응답에 실린 시각이 맞는지 확인할 방법이 없었다.
     */
    @Test
    void transitionChangedAtComesFromInjectedClock() throws Exception {
        Long meetingId = createMeeting(registrantId);

        mockMvc.perform(transition(meetingId, "OPEN", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changedAt").value("2026-09-03T19:30:00+09:00"));
    }

    // 의장이 아닌 회원은 MEETING_MANAGE가 있어도 개회·회의록작성·종료를 할 수 없다 (TR-M1~M3)
    @Test
    void transitionOpenByNonChairReturns403Forbidden() throws Exception {
        Long meetingId = createMeeting(otherMemberId); // 의장은 otherMemberId, 요청자는 registrant

        mockMvc.perform(transition(meetingId, "OPEN", null))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void transitionCloseWithoutOpenReturns409TransitionNotAllowed() throws Exception {
        Long meetingId = createMeeting(registrantId);

        mockMvc.perform(transition(meetingId, "CLOSE", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSITION_NOT_ALLOWED"));
    }

    @Test
    void transitionCloseBlockedByPendingAgendaReturns409AgendaUnresolved() throws Exception {
        Long meetingId = createMeetingWithOneLinkedAgenda(registrantId);
        mockMvc.perform(transition(meetingId, "OPEN", null)).andExpect(status().isOk());
        mockMvc.perform(transition(meetingId, "WRITE_MINUTES", null)).andExpect(status().isOk());

        mockMvc.perform(transition(meetingId, "CLOSE", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AGENDA_UNRESOLVED"));
    }

    @Test
    void transitionCloseSucceedsWhenAgendaResolved() throws Exception {
        String response = createMeetingWithOneLinkedAgendaAndCapture(registrantId);
        Long meetingId = JsonPath.parse(response).read("$.data.meetingId", Long.class);
        Long agendaId = JsonPath.parse(response).read("$.data.agendas[0].agendaId", Long.class);

        mockMvc.perform(transition(meetingId, "OPEN", null)).andExpect(status().isOk());
        mockMvc.perform(transition(meetingId, "WRITE_MINUTES", null)).andExpect(status().isOk());
        mockMvc.perform(updateAgenda(meetingId, agendaId, "HOLD")).andExpect(status().isOk());

        mockMvc.perform(transition(meetingId, "CLOSE", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.meetingStatus").value("CLOSED"));
    }

    @Test
    void transitionCancelWithoutReasonReturns422ReasonRequired() throws Exception {
        Long meetingId = createMeeting(registrantId);

        mockMvc.perform(transition(meetingId, "CANCEL", null))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
    }

    // 취소(TR-M4)는 정의서가 '의장·국장 이상'을 함께 허용하므로 의장이 아니어도 된다
    @Test
    void transitionCancelByNonChairSucceeds() throws Exception {
        Long meetingId = createMeeting(otherMemberId);

        mockMvc.perform(transition(meetingId, "CANCEL", "일정 취소"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.meetingStatus").value("CANCELED"));
    }

    // ------------------------------------------------------------------ 안건

    @Test
    void addAgendaReturns201WithItsOperation() throws Exception {
        Long meetingId = createMeeting(otherMemberId);
        String body =
                """
                {"targetOperationId": %d, "content": "논의할 내용"}
                """
                        .formatted(linkedOperationId);

        mockMvc.perform(authenticated(post("/v1/meetings/{meetingId}/agendas", meetingId), body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.targetOperation.operationId").value(linkedOperationId))
                .andExpect(jsonPath("$.data.processStatus").value("PENDING"));
    }

    /*
     * targetId는 운영 유형의 상세 ID다(#635) — 업무면 work_id, 하위 업무면 sub_work_id, 회의면
     * mtg_id. 화면이 운영 ID로 업무 상세를 열어 엉뚱한 업무가 열렸다. 등록·목록·상세 세 응답이
     * 같은 값을 싣는지 본다.
     */
    @Test
    void agendaTargetCarriesDetailIdPerOperationType() throws Exception {
        Long subWorkTypeId =
                SubWorkTypeFixture.idOf(subWorkTypeRepository, SubWorkTypeFixture.EXPENDITURE);
        SubWorkCreateResponse subWork =
                subWorkService.createSubWork(
                        new SubWorkCreateRequest(
                                linkedWorkId,
                                "부스 물품 구매",
                                subWorkTypeId,
                                otherMemberId,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null),
                        registrant);
        Long targetMeetingId = createMeeting(otherMemberId);
        String targetMeeting =
                mockMvc.perform(authenticated(get("/v1/meetings/{meetingId}", targetMeetingId)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        Long targetMeetingOperationId =
                JsonPath.parse(targetMeeting).read("$.data.operationId", Long.class);

        String body =
                """
                {
                  "title": "9월 1차 정기회의",
                  "meetingCategory": "REGULAR",
                  "personInChargeId": %d,
                  "startAt": "2026-09-03T19:00:00+09:00",
                  "agendas": [
                    {"targetOperationId": %d},
                    {"targetOperationId": %d},
                    {"targetOperationId": %d},
                    {"agendaName": "드래프트"}
                  ]
                }
                """
                        .formatted(
                                otherMemberId,
                                linkedOperationId,
                                subWork.operationId(),
                                targetMeetingOperationId);
        String created =
                mockMvc.perform(authenticated(post("/v1/meetings"), body))
                        .andExpect(status().isCreated())
                        .andExpect(
                                jsonPath("$.data.agendas[0].targetOperation.targetId")
                                        .value(linkedWorkId))
                        .andExpect(
                                jsonPath("$.data.agendas[1].targetOperation.targetId")
                                        .value(subWork.subWorkId()))
                        .andExpect(
                                jsonPath("$.data.agendas[2].targetOperation.targetId")
                                        .value(targetMeetingId))
                        .andExpect(jsonPath("$.data.agendas[3].targetOperation").doesNotExist())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        Long meetingId = JsonPath.parse(created).read("$.data.meetingId", Long.class);

        mockMvc.perform(authenticated(get("/v1/meetings/{meetingId}/agendas", meetingId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].targetOperation.operationType").value("WORK"))
                .andExpect(jsonPath("$.data[0].targetOperation.targetId").value(linkedWorkId))
                .andExpect(jsonPath("$.data[1].targetOperation.operationType").value("SUB_WORK"))
                .andExpect(
                        jsonPath("$.data[1].targetOperation.targetId").value(subWork.subWorkId()))
                .andExpect(jsonPath("$.data[2].targetOperation.operationType").value("MEETING"))
                .andExpect(jsonPath("$.data[2].targetOperation.targetId").value(targetMeetingId));
        mockMvc.perform(authenticated(get("/v1/meetings/{meetingId}", meetingId)))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data.agendas[0].targetOperation.targetId").value(linkedWorkId))
                .andExpect(
                        jsonPath("$.data.agendas[1].targetOperation.targetId")
                                .value(subWork.subWorkId()))
                .andExpect(
                        jsonPath("$.data.agendas[2].targetOperation.targetId")
                                .value(targetMeetingId));
    }

    /** 없는 운영 건은 404 — 안건이 그것을 가리키는 것이 전제라 여기서 끊긴다 (#593). */
    @Test
    void addAgendaWithUnknownOperationReturns404() throws Exception {
        Long meetingId = createMeeting(otherMemberId);
        String body = """
                {"targetOperationId": 999999}
                """;

        mockMvc.perform(authenticated(post("/v1/meetings/{meetingId}/agendas", meetingId), body))
                .andExpect(status().isNotFound());
    }

    @Test
    void addAgendaOnClosedMeetingReturns409MeetingClosed() throws Exception {
        Long meetingId = createMeeting(otherMemberId);
        mockMvc.perform(transition(meetingId, "CANCEL", "일정 취소")).andExpect(status().isOk());

        String body =
                """
                {"targetOperationId": %d}
                """
                        .formatted(linkedOperationId);
        mockMvc.perform(authenticated(post("/v1/meetings/{meetingId}/agendas", meetingId), body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEETING_CLOSED"));
    }

    @Test
    void updateAgendaReturns200WithUpdatedFields() throws Exception {
        String response = createMeetingWithOneLinkedAgendaAndCapture(otherMemberId);
        Long meetingId = JsonPath.parse(response).read("$.data.meetingId", Long.class);
        Long agendaId = JsonPath.parse(response).read("$.data.agendas[0].agendaId", Long.class);

        mockMvc.perform(updateAgenda(meetingId, agendaId, "CLOSED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processStatus").value("CLOSED"))
                .andExpect(jsonPath("$.data.resultContent").value("원안 가결"));
    }

    @Test
    void withdrawAgendaWhileScheduledReturns200() throws Exception {
        String response = createMeetingWithOneLinkedAgendaAndCapture(otherMemberId);
        Long meetingId = JsonPath.parse(response).read("$.data.meetingId", Long.class);
        Long agendaId = JsonPath.parse(response).read("$.data.agendas[0].agendaId", Long.class);

        mockMvc.perform(
                        authenticated(
                                delete(
                                        "/v1/meetings/{meetingId}/agendas/{agendaId}",
                                        meetingId,
                                        agendaId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    // 회의가 시작된 뒤에는 안건을 상정 철회할 수 없다 (OPS-029 "회의 시작 전만")
    @Test
    void withdrawAgendaAfterStartReturns409TransitionNotAllowed() throws Exception {
        String response = createMeetingWithOneLinkedAgendaAndCapture(registrantId);
        Long meetingId = JsonPath.parse(response).read("$.data.meetingId", Long.class);
        Long agendaId = JsonPath.parse(response).read("$.data.agendas[0].agendaId", Long.class);
        mockMvc.perform(transition(meetingId, "OPEN", null)).andExpect(status().isOk());

        mockMvc.perform(
                        authenticated(
                                delete(
                                        "/v1/meetings/{meetingId}/agendas/{agendaId}",
                                        meetingId,
                                        agendaId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSITION_NOT_ALLOWED"));
    }

    // ------------------------------------------------------------------ 드래프트 안건 (#625 · ADR-0059)

    @Test
    void addDraftAgendaReturns201WithItsNameAndNoOperation() throws Exception {
        Long meetingId = createMeeting(otherMemberId);

        mockMvc.perform(addDraftAgenda(meetingId, "동아리방 정리 당번"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.agendaName").value("동아리방 정리 당번"))
                .andExpect(jsonPath("$.data.draft").value(true))
                .andExpect(jsonPath("$.data.targetOperation").doesNotExist())
                .andExpect(jsonPath("$.data.processStatus").value("PENDING"));
    }

    // 연결 안건은 제목을 갖지 않고 draft=false다 — 화면이 null 검사로 추론하지 않게 따로 싣는다
    @Test
    void linkedAgendaIsNotDraftAndHasNoName() throws Exception {
        String response = createMeetingWithOneLinkedAgendaAndCapture(otherMemberId);

        assertThat(JsonPath.parse(response).read("$.data.agendas[0].draft", Boolean.class))
                .isFalse();
        assertThat((Object) JsonPath.parse(response).read("$.data.agendas[0].agendaName")).isNull();
    }

    // 공백뿐인 제목은 없는 것과 같다 — 운영 건도 없으니 «둘 중 하나»를 어긴다
    @Test
    void addAgendaWithBlankNameReturnsValidationFailed() throws Exception {
        Long meetingId = createMeeting(otherMemberId);

        mockMvc.perform(addDraftAgenda(meetingId, "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void updateDraftAgendaRenamesIt() throws Exception {
        Long meetingId = createMeeting(otherMemberId);
        Long agendaId = draftAgendaId(meetingId, "동아리방 정리");
        String body =
                """
                {"agendaName": "동아리방 정리 당번 정하기", "content": "주 1회", "processStatus": "HOLD"}
                """;

        mockMvc.perform(
                        authenticated(
                                patch(
                                        "/v1/meetings/{meetingId}/agendas/{agendaId}",
                                        meetingId,
                                        agendaId),
                                body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.agendaName").value("동아리방 정리 당번 정하기"))
                .andExpect(jsonPath("$.data.draft").value(true))
                .andExpect(jsonPath("$.data.processStatus").value("HOLD"));
    }

    // 제목을 싣지 않는 수정(이 필드 이전의 화면)은 드래프트의 제목을 지우지 않는다
    @Test
    void updateDraftAgendaWithoutNameKeepsIt() throws Exception {
        Long meetingId = createMeeting(otherMemberId);
        Long agendaId = draftAgendaId(meetingId, "동아리방 정리");

        mockMvc.perform(updateAgenda(meetingId, agendaId, "CLOSED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.agendaName").value("동아리방 정리"))
                .andExpect(jsonPath("$.data.resultContent").value("원안 가결"));
    }

    // 연결 안건의 제목은 운영 건의 제목이다 — 제목을 주면 400 (엔티티가 판정한다)
    @Test
    void updateLinkedAgendaWithNameReturnsValidationFailed() throws Exception {
        String response = createMeetingWithOneLinkedAgendaAndCapture(otherMemberId);
        Long meetingId = JsonPath.parse(response).read("$.data.meetingId", Long.class);
        Long agendaId = JsonPath.parse(response).read("$.data.agendas[0].agendaId", Long.class);
        String body =
                """
                {"agendaName": "다른 제목", "processStatus": "PENDING"}
                """;

        mockMvc.perform(
                        authenticated(
                                patch(
                                        "/v1/meetings/{meetingId}/agendas/{agendaId}",
                                        meetingId,
                                        agendaId),
                                body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void promoteDraftAgendaCreatesWorkAndLinksIt() throws Exception {
        Long meetingId = createMeeting(otherMemberId);
        Long agendaId = draftAgendaId(meetingId, "동아리방 정리 당번");

        String response =
                mockMvc.perform(promote(meetingId, agendaId, "동아리방 정리 당번"))
                        .andExpect(status().isCreated())
                        .andExpect(header().exists("Location"))
                        .andExpect(jsonPath("$.data.work.title").value("동아리방 정리 당번"))
                        .andExpect(jsonPath("$.data.work.itemType").value("ROUTINE"))
                        .andExpect(jsonPath("$.data.work.ownerId").value(otherMemberId))
                        .andExpect(jsonPath("$.data.work.registrantId").value(registrantId))
                        .andExpect(jsonPath("$.data.agenda.agendaId").value(agendaId))
                        .andExpect(jsonPath("$.data.agenda.draft").value(false))
                        .andExpect(jsonPath("$.data.agenda.agendaName").doesNotExist())
                        .andExpect(
                                jsonPath("$.data.agenda.targetOperation.operationType")
                                        .value("WORK"))
                        .andExpect(
                                jsonPath("$.data.agenda.targetOperation.title").value("동아리방 정리 당번"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        Long operationId = JsonPath.parse(response).read("$.data.work.operationId", Long.class);
        Long workId = JsonPath.parse(response).read("$.data.work.workId", Long.class);
        assertThat(
                        JsonPath.parse(response)
                                .read("$.data.agenda.targetOperation.targetId", Long.class))
                .isEqualTo(workId);

        // 다시 읽어도 안건이 그 업무를 가리킨다 — 응답만이 아니라 저장된 상태다
        mockMvc.perform(authenticated(get("/v1/meetings/{meetingId}/agendas", meetingId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].targetOperation.operationId").value(operationId))
                .andExpect(jsonPath("$.data[0].draft").value(false));
        mockMvc.perform(authenticated(get("/v1/works/{workId}", workId)))
                .andExpect(status().isOk());
    }

    // 업무 제목은 요청이 준다 — 서버가 안건 제목으로 채우지 않는다(ADR-0059 «승격의 필수 값»)
    @Test
    void promoteWithoutTitleReturnsValidationFailed() throws Exception {
        Long meetingId = createMeeting(otherMemberId);
        Long agendaId = draftAgendaId(meetingId, "동아리방 정리 당번");
        String body =
                """
                {"itemType": "ROUTINE", "ownerId": %d}
                """
                        .formatted(otherMemberId);

        mockMvc.perform(
                        authenticated(
                                post(
                                        "/v1/meetings/{meetingId}/agendas/{agendaId}/promote",
                                        meetingId,
                                        agendaId),
                                body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // 승격은 한 번뿐이다 — 이미 운영 건을 가리키는 안건은 409
    @Test
    void promoteLinkedAgendaReturns409AlreadyLinked() throws Exception {
        String response = createMeetingWithOneLinkedAgendaAndCapture(otherMemberId);
        Long meetingId = JsonPath.parse(response).read("$.data.meetingId", Long.class);
        Long agendaId = JsonPath.parse(response).read("$.data.agendas[0].agendaId", Long.class);

        mockMvc.perform(promote(meetingId, agendaId, "또 만들기"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEETING_AGENDA_ALREADY_LINKED"));
    }

    // 종료된 회의에서도 승격한다 — 승격은 안건 내용을 고치는 게 아니라 업무를 만드는 일이다(#634)
    @Test
    void promoteOnClosedMeetingCreatesWork() throws Exception {
        Long meetingId = createMeeting(registrantId);
        Long agendaId = draftAgendaId(meetingId, "동아리방 정리 당번");
        mockMvc.perform(transition(meetingId, "OPEN", null)).andExpect(status().isOk());
        mockMvc.perform(transition(meetingId, "WRITE_MINUTES", null)).andExpect(status().isOk());
        mockMvc.perform(updateAgenda(meetingId, agendaId, "HOLD")).andExpect(status().isOk());
        mockMvc.perform(transition(meetingId, "CLOSE", null)).andExpect(status().isOk());

        mockMvc.perform(promote(meetingId, agendaId, "동아리방 정리 당번"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.agenda.draft").value(false))
                .andExpect(jsonPath("$.data.work.title").value("동아리방 정리 당번"));
    }

    @Test
    void promoteOnCanceledMeetingCreatesWork() throws Exception {
        Long meetingId = createMeeting(otherMemberId);
        Long agendaId = draftAgendaId(meetingId, "동아리방 정리 당번");
        mockMvc.perform(transition(meetingId, "CANCEL", "일정 취소")).andExpect(status().isOk());

        mockMvc.perform(promote(meetingId, agendaId, "동아리방 정리 당번"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.agenda.draft").value(false));
    }

    // 승격만 열었다 — 취소된 회의의 드래프트 안건 수정은 그대로 409다(#634)
    @Test
    void updateDraftAgendaOnCanceledMeetingReturns409MeetingClosed() throws Exception {
        Long meetingId = createMeeting(otherMemberId);
        Long agendaId = draftAgendaId(meetingId, "동아리방 정리 당번");
        mockMvc.perform(transition(meetingId, "CANCEL", "일정 취소")).andExpect(status().isOk());

        mockMvc.perform(updateAgenda(meetingId, agendaId, "HOLD"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEETING_CLOSED"));
    }

    @Test
    void promoteUnknownAgendaReturns404() throws Exception {
        Long meetingId = createMeeting(otherMemberId);

        mockMvc.perform(promote(meetingId, 999999L, "없는 안건")).andExpect(status().isNotFound());
    }

    // 국원은 안건을 쓸 수 있지만(MEETING_AGENDA_WRITE) 업무를 만들 수는 없다 — 승격은 WORK_MANAGE다
    @Test
    void promoteWithoutWorkManageReturns403() throws Exception {
        Long meetingId = createMeeting(otherMemberId);
        Long agendaId = draftAgendaId(meetingId, "동아리방 정리 당번");
        UUID staffToken = UUID.randomUUID();
        MemberEntity staff = saveMember(staffToken, "20200004", "최민지", "staff@sscc.org");
        MemberRoleFixture.assign(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                staff,
                "국원");
        String body =
                """
                {"title": "동아리방 정리 당번", "itemType": "ROUTINE", "ownerId": %d}
                """
                        .formatted(otherMemberId);

        mockMvc.perform(
                        post(
                                        "/v1/meetings/{meetingId}/agendas/{agendaId}/promote",
                                        meetingId,
                                        agendaId)
                                .header("Authorization", "Bearer " + staffToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // ------------------------------------------------------------------ 하위 업무 승격 (#644)

    @Test
    void promoteDraftAgendaToSubWorkCreatesSubWorkAndLinksIt() throws Exception {
        Long meetingId = createMeeting(otherMemberId);
        Long agendaId = draftAgendaId(meetingId, "부스 배치도 확정");

        MvcResult result =
                mockMvc.perform(promoteSubWork(meetingId, agendaId, subWorkBody(linkedWorkId)))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.subWork.title").value("부스 배치도 확정"))
                        .andExpect(jsonPath("$.data.subWork.workId").value(linkedWorkId))
                        .andExpect(jsonPath("$.data.subWork.ownerId").value(otherMemberId))
                        .andExpect(jsonPath("$.data.subWork.registrantId").value(registrantId))
                        .andExpect(jsonPath("$.data.agenda.agendaId").value(agendaId))
                        .andExpect(jsonPath("$.data.agenda.draft").value(false))
                        .andExpect(jsonPath("$.data.agenda.agendaName").doesNotExist())
                        .andExpect(
                                jsonPath("$.data.agenda.targetOperation.operationType")
                                        .value("SUB_WORK"))
                        .andReturn();
        String response = result.getResponse().getContentAsString();
        Long subWorkId = JsonPath.parse(response).read("$.data.subWork.subWorkId", Long.class);
        Long operationId = JsonPath.parse(response).read("$.data.subWork.operationId", Long.class);
        assertThat(result.getResponse().getHeader("Location"))
                .isEqualTo("/v1/sub-works/" + subWorkId);
        // 상세를 여는 값은 운영 ID가 아니라 하위 업무 ID다(#635)
        assertThat(
                        JsonPath.parse(response)
                                .read("$.data.agenda.targetOperation.targetId", Long.class))
                .isEqualTo(subWorkId);

        // 다시 읽어도 안건이 그 하위 업무를 가리킨다 — 응답만이 아니라 저장된 상태다
        mockMvc.perform(authenticated(get("/v1/meetings/{meetingId}/agendas", meetingId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].targetOperation.operationId").value(operationId))
                .andExpect(jsonPath("$.data[0].draft").value(false));
        mockMvc.perform(authenticated(get("/v1/sub-works/{subWorkId}", subWorkId)))
                .andExpect(status().isOk());
    }

    // 상위 업무는 요청이 준다 — 하위 업무 등록과 같은 검증(workId 필수)
    @Test
    void promoteToSubWorkWithoutWorkIdReturnsValidationFailed() throws Exception {
        Long meetingId = createMeeting(otherMemberId);
        Long agendaId = draftAgendaId(meetingId, "부스 배치도 확정");
        String body =
                """
                {"title": "부스 배치도 확정", "subWorkTypeId": %d, "ownerId": %d}
                """
                        .formatted(approvalFreeTypeId(), otherMemberId);

        mockMvc.perform(promoteSubWork(meetingId, agendaId, body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // 하위 업무 등록의 거절이 그대로 난다 — 없는 상위 업무는 404
    @Test
    void promoteToSubWorkWithUnknownWorkReturns404() throws Exception {
        Long meetingId = createMeeting(otherMemberId);
        Long agendaId = draftAgendaId(meetingId, "부스 배치도 확정");

        mockMvc.perform(promoteSubWork(meetingId, agendaId, subWorkBody(999_999L)))
                .andExpect(status().isNotFound());
    }

    // 업무 승격과 같다 — 이미 운영 건을 가리키는 안건은 하위 업무를 만들기 전에 409
    @Test
    void promoteLinkedAgendaToSubWorkReturns409AlreadyLinked() throws Exception {
        String response = createMeetingWithOneLinkedAgendaAndCapture(otherMemberId);
        Long meetingId = JsonPath.parse(response).read("$.data.meetingId", Long.class);
        Long agendaId = JsonPath.parse(response).read("$.data.agendas[0].agendaId", Long.class);

        mockMvc.perform(promoteSubWork(meetingId, agendaId, subWorkBody(linkedWorkId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEETING_AGENDA_ALREADY_LINKED"));
    }

    // 종료된 회의에서도 하위 업무로 승격한다(#634와 같다)
    @Test
    void promoteToSubWorkOnClosedMeetingCreatesSubWork() throws Exception {
        Long meetingId = createMeeting(registrantId);
        Long agendaId = draftAgendaId(meetingId, "부스 배치도 확정");
        mockMvc.perform(transition(meetingId, "OPEN", null)).andExpect(status().isOk());
        mockMvc.perform(transition(meetingId, "WRITE_MINUTES", null)).andExpect(status().isOk());
        mockMvc.perform(updateAgenda(meetingId, agendaId, "HOLD")).andExpect(status().isOk());
        mockMvc.perform(transition(meetingId, "CLOSE", null)).andExpect(status().isOk());

        mockMvc.perform(promoteSubWork(meetingId, agendaId, subWorkBody(linkedWorkId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.agenda.draft").value(false))
                .andExpect(jsonPath("$.data.subWork.workId").value(linkedWorkId));
    }

    // 국원은 안건을 쓸 수 있지만 하위 업무를 만들 수는 없다 — 하위 업무 승격도 WORK_MANAGE다
    @Test
    void promoteToSubWorkWithoutWorkManageReturns403() throws Exception {
        Long meetingId = createMeeting(otherMemberId);
        Long agendaId = draftAgendaId(meetingId, "부스 배치도 확정");
        UUID staffToken = UUID.randomUUID();
        MemberEntity staff = saveMember(staffToken, "20200004", "최민지", "staff@sscc.org");
        MemberRoleFixture.assign(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                staff,
                "국원");

        mockMvc.perform(
                        post(promoteSubWorkPath(), meetingId, agendaId)
                                .header("Authorization", "Bearer " + staffToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(subWorkBody(linkedWorkId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // ------------------------------------------------------------------ 헬퍼

    // ------------------------------------------------------------------ 삭제 (#125)

    @Test
    void deleteMeetingReturns200() throws Exception {
        Long meetingId = createMeeting(otherMemberId);

        mockMvc.perform(authenticated(delete("/v1/meetings/{meetingId}", meetingId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(
                        get("/v1/meetings/{meetingId}", meetingId)
                                .header("Authorization", "Bearer " + AUTH_USER_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /*
     * 상태와 무관하게 항상 삭제할 수 있다 — 종료·취소된 회의도 예외가 아니다(#125 결정).
     * CANCEL은 SCHEDULED에서 바로 갈 수 있는 가장 짧은 경로라 이 경로로 확인한다.
     */
    @Test
    void deleteMeetingAllowsCanceledMeeting() throws Exception {
        Long meetingId = createMeeting(otherMemberId);
        mockMvc.perform(transition(meetingId, "CANCEL", "일정 취소")).andExpect(status().isOk());

        mockMvc.perform(authenticated(delete("/v1/meetings/{meetingId}", meetingId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void deleteUnknownMeetingReturns404() throws Exception {
        mockMvc.perform(authenticated(delete("/v1/meetings/{meetingId}", 999_999L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void deleteAlreadyDeletedMeetingReturns409() throws Exception {
        Long meetingId = createMeeting(otherMemberId);
        mockMvc.perform(authenticated(delete("/v1/meetings/{meetingId}", meetingId)))
                .andExpect(status().isOk());

        mockMvc.perform(authenticated(delete("/v1/meetings/{meetingId}", meetingId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_DELETED"));
    }

    @Test
    void deleteMeetingWithoutTokenReturns401() throws Exception {
        mockMvc.perform(delete("/v1/meetings/{meetingId}", 1L))
                .andExpect(status().isUnauthorized());
    }

    private Long createMeeting(Long personInChargeId) throws Exception {
        String body =
                """
                {
                  "title": "9월 1차 정기회의",
                  "meetingCategory": "REGULAR",
                  "personInChargeId": %d,
                  "startAt": "2026-09-03T19:00:00+09:00"
                }
                """
                        .formatted(personInChargeId);
        String response =
                mockMvc.perform(authenticated(post("/v1/meetings"), body))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.parse(response).read("$.data.meetingId", Long.class);
    }

    private Long createMeetingWithOneLinkedAgenda(Long personInChargeId) throws Exception {
        return JsonPath.parse(createMeetingWithOneLinkedAgendaAndCapture(personInChargeId))
                .read("$.data.meetingId", Long.class);
    }

    private String createMeetingWithOneLinkedAgendaAndCapture(Long personInChargeId)
            throws Exception {
        String body =
                """
                {
                  "title": "9월 1차 정기회의",
                  "meetingCategory": "REGULAR",
                  "personInChargeId": %d,
                  "startAt": "2026-09-03T19:00:00+09:00",
                  "agendas": [
                    {"targetOperationId": %d, "content": "박람회 부스 배치", "processStatus": "PENDING"}
                  ]
                }
                """
                        .formatted(personInChargeId, linkedOperationId);
        return mockMvc.perform(authenticated(post("/v1/meetings"), body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private MockHttpServletRequestBuilder addDraftAgenda(Long meetingId, String agendaName) {
        String body = "{\"agendaName\": \"%s\"}".formatted(agendaName);
        return authenticated(post("/v1/meetings/{meetingId}/agendas", meetingId), body);
    }

    private Long draftAgendaId(Long meetingId, String agendaName) throws Exception {
        String response =
                mockMvc.perform(addDraftAgenda(meetingId, agendaName))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.parse(response).read("$.data.agendaId", Long.class);
    }

    private MockHttpServletRequestBuilder promote(Long meetingId, Long agendaId, String title) {
        String body =
                """
                {"title": "%s", "itemType": "ROUTINE", "ownerId": %d}
                """
                        .formatted(title, otherMemberId);
        return authenticated(
                post("/v1/meetings/{meetingId}/agendas/{agendaId}/promote", meetingId, agendaId),
                body);
    }

    private static String promoteSubWorkPath() {
        return "/v1/meetings/{meetingId}/agendas/{agendaId}/promote-sub-work";
    }

    private MockHttpServletRequestBuilder promoteSubWork(
            Long meetingId, Long agendaId, String body) {
        return authenticated(post(promoteSubWorkPath(), meetingId, agendaId), body);
    }

    private String subWorkBody(Long workId) {
        return """
                {"workId": %d, "title": "부스 배치도 확정", "subWorkTypeId": %d, "ownerId": %d}
                """
                .formatted(workId, approvalFreeTypeId(), otherMemberId);
    }

    private Long approvalFreeTypeId() {
        return SubWorkTypeFixture.idOf(subWorkTypeRepository, SubWorkTypeFixture.APPROVAL_FREE);
    }

    private MockHttpServletRequestBuilder transition(Long meetingId, String action, String reason)
            throws Exception {
        String body =
                reason == null
                        ? "{\"transition\": \"%s\"}".formatted(action)
                        : "{\"transition\": \"%s\", \"reason\": \"%s\"}".formatted(action, reason);
        return authenticated(post("/v1/meetings/{meetingId}/transitions", meetingId), body);
    }

    private MockHttpServletRequestBuilder updateAgenda(
            Long meetingId, Long agendaId, String processStatus) {
        String body =
                """
                {"content": "장소 후보 3곳 답사", "resultContent": "원안 가결", "processStatus": "%s"}
                """
                        .formatted(processStatus);
        return authenticated(
                patch("/v1/meetings/{meetingId}/agendas/{agendaId}", meetingId, agendaId), body);
    }

    private MemberEntity saveMember(
            UUID authUserId, String studentNumber, String name, String email) {
        return MemberFixture.save(
                memberRepository,
                memberGradeRepository,
                memberStatusRepository,
                authUserId,
                studentNumber,
                name,
                email);
    }

    // 공용 디코더는 토큰 문자열을 그대로 sub로 쓰므로 기본 토큰은 registrant의 sub다
    private static MockHttpServletRequestBuilder authenticated(
            MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + AUTH_USER_ID);
    }

    private static MockHttpServletRequestBuilder authenticated(
            MockHttpServletRequestBuilder builder, String body) {
        return builder.header("Authorization", "Bearer " + AUTH_USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    @TestConfiguration
    static class FixedClockConfig {

        /*
         * 전이 일시(changedAt)를 응답에서 검증하려면 기준 시각이 고정돼야 한다 (#117).
         * 역할 배정 시작일(MemberRoleFixture — 2026-03-01) 이후여야 MEETING_MANAGE가
         * 유효하므로 회의 일정과 같은 날로 둔다.
         */
        // 빈 이름을 ClockConfig의 'clock'과 다르게 둔다 — 같으면 정의 덮어쓰기가 막혀 컨텍스트가 뜨지 않는다
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(TRANSITION_NOW.toInstant(), KST);
        }
    }
}
