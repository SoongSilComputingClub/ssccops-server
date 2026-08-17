package org.sscc.ssccopsserver.domain.operation.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleClassificationRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.domain.operation.dto.WorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.entity.WorkType;
import org.sscc.ssccopsserver.domain.operation.service.WorkService;
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.MemberRoleFixture;

import com.jayway.jsonpath.JsonPath;

/*
 * 회의 API (OPS-024~029, #83). JwtDecoder를 대체해 필터체인 전체를 태우는 방식은
 * WorkControllerTest·SubWorkControllerTest와 같다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MeetingControllerTest.StubJwtDecoderConfig.class)
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

    private Long otherMemberId;
    private Long registrantId;
    private Long linkedOperationId;

    @BeforeEach
    void setUp() throws Exception {
        otherMemberId = saveMember(UUID.randomUUID(), "20200001", "김도현", "owner@sscc.org").getId();
        // 토큰의 sub(AUTH_USER_ID)와 연결된 회원. 회의 책임자로도 쓰여 전이 권한 테스트가 이 회원을 의장으로 삼는다
        MemberEntity registrant = saveMember(AUTH_USER_ID, "20200002", "이서연", "actor@sscc.org");
        registrantId = registrant.getId();

        // 회의 API는 MEETING_MANAGE를 요구한다(#9 준용). 국장이 OPERATOR를 통해 닿는다
        MemberRoleFixture.assign(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                registrant,
                MemberRoleFixture.DIRECTOR);

        // 안건이 연결할 운영 건(업무) 하나
        linkedOperationId =
                workService
                        .createWork(
                                new WorkCreateRequest(
                                        "2026 동아리 박람회",
                                        WorkType.EVENT,
                                        otherMemberId,
                                        null,
                                        null,
                                        null,
                                        null),
                                registrant)
                        .operationId();
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
                    {"agendaName": "신입 모집 일정", "processStatus": "PENDING"}
                  ]
                }
                """
                        .formatted(otherMemberId, linkedOperationId);

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
                .andExpect(jsonPath("$.data.agendas[1].agendaName").value("신입 모집 일정"))
                .andExpect(jsonPath("$.data.agendas[1].targetOperation").doesNotExist());
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

    // 안건은 연결 운영 건·독립 제목 중 하나만 받는다 (OPS-027 "둘 중 하나 필수")
    @Test
    void agendaWithBothTargetAndNameReturnsValidationFailed() throws Exception {
        String body =
                """
                {
                  "title": "안건 둘 다 지정",
                  "meetingCategory": "TOPIC",
                  "personInChargeId": %d,
                  "startAt": "2026-09-03T19:00:00+09:00",
                  "agendas": [{"targetOperationId": %d, "agendaName": "둘 다"}]
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
    void addStandaloneAgendaReturns201() throws Exception {
        Long meetingId = createMeeting(otherMemberId);
        String body =
                """
                {"agendaName": "임시 안건", "content": "논의할 내용"}
                """;

        mockMvc.perform(authenticated(post("/v1/meetings/{meetingId}/agendas", meetingId), body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.agendaName").value("임시 안건"))
                .andExpect(jsonPath("$.data.processStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.targetOperation").doesNotExist());
    }

    @Test
    void addAgendaOnClosedMeetingReturns409MeetingClosed() throws Exception {
        Long meetingId = createMeeting(otherMemberId);
        mockMvc.perform(transition(meetingId, "CANCEL", "일정 취소")).andExpect(status().isOk());

        String body = """
                {"agendaName": "취소된 회의의 안건"}
                """;
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

    // ------------------------------------------------------------------ 헬퍼

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

    // 토큰 문자열을 그대로 sub로 쓰는 스텁 디코더라(AuthorityControllerTest와 같은 방식), 기본 토큰은 registrant의 sub다
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
    static class StubJwtDecoderConfig {

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

        @Bean
        @Primary
        JwtDecoder jwtDecoder() {
            return token ->
                    Jwt.withTokenValue(token)
                            .header("alg", "none")
                            .subject(token)
                            .claim("email", "actor@sscc.org")
                            .issuedAt(Instant.now())
                            .expiresAt(Instant.now().plusSeconds(60))
                            .build();
        }
    }
}
