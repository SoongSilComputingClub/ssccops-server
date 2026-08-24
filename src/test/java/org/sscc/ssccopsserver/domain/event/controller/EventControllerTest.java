package org.sscc.ssccopsserver.domain.event.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.hamcrest.Matchers;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import org.sscc.ssccopsserver.domain.event.code.EventParticipantStatus;
import org.sscc.ssccopsserver.domain.event.dto.EventSummaryResponse;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.event.entity.EventParticipantEntity;
import org.sscc.ssccopsserver.domain.event.repository.EventParticipantRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventRepository;
import org.sscc.ssccopsserver.domain.event.service.EventService;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;
import org.sscc.ssccopsserver.domain.form.entity.ResponseContent;
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
import org.sscc.ssccopsserver.support.AuthorityFixture;
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.MemberRoleFixture;

import com.jayway.jsonpath.JsonPath;

/*
 * 행사 CRUD·게시 전이 API(ssccops#139) 통합 검증. 필터체인 전체를 태우기 위해 JwtDecoder만
 * 토큰 문자열을 그대로 sub로 쓰도록 대체한다 — 요청 주체를 요청마다 바꿔야 해서
 * RoleClassificationControllerTest와 같은 방식이다.
 *
 * eventPhase·receiptStatus가 주입된 Clock에서 오는지 확인해야 하므로 시각도 고정한다
 * (FormControllerTest 선례). 고정 시각은 2026-03-15 00:00 KST다.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EventControllerTest.StubJwtDecoderConfig.class)
@Transactional
class EventControllerTest {

    /** 고정 기준 시각 (2026-03-15 00:00 KST). 행사 일시 표본은 이 값을 사이에 두고 앞뒤로 잡는다 */
    private static final Instant NOW = Instant.parse("2026-03-14T15:00:00Z");

    private static final String EVENTS = "/v1/events";

    /** 폼 연결 표본용 두 문항짜리 구성 (FormControllerTest의 표본 축약) */
    private static final String VALID_COMPOSITION =
            """
            {
              "pages": [{"pageTtl": "기본 정보", "pageDescCn": null}],
              "qitems": [{
                "qitemId": "q1", "qitemLblNm": "이름", "qitemTypeCd": "SHORT_TEXT",
                "reqYn": true, "pageSeq": 0, "optionList": []
              }]
            }
            """;

    @PersistenceContext private EntityManager entityManager;

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
    @Autowired private EventParticipantRepository eventParticipantRepository;
    @Autowired private FormRepository formRepository;
    @Autowired private FormResponseHistoryRepository formResponseHistoryRepository;
    @Autowired private EventService eventService;

    private UUID managerToken;
    private UUID outsiderToken;
    private MemberEntity manager;

    @BeforeEach
    void setUp() {
        /*
         * 국장(OPERATOR)은 EVENT_MANAGE(시드에서 OPERATOR의 자식)와 폼 권한에 함께 닿는다 —
         * 폼 연결 표본을 폼 API로 만들기 때문에 EVENT_MANAGE 하나짜리 역할 대신 이것을 쓴다.
         */
        managerToken = UUID.randomUUID();
        manager = saveMember(managerToken, "20260001", "행사운영자");
        MemberRoleFixture.assign(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                manager,
                MemberRoleFixture.DIRECTOR);

        // EVENT_MANAGE가 없는 회원. '다른 권한만' 가진 쪽이어야 403이 권한 때문이라는 것이 드러난다
        outsiderToken = UUID.randomUUID();
        MemberEntity outsider = saveMember(outsiderToken, "20260002", "업무담당");
        AuthorityFixture.grant(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                authorityRepository,
                roleAuthorityRelationRepository,
                outsider,
                AuthorityCode.WORK_MANAGE);
    }

    /* ── 생성 ─────────────────────────────────────────────── */

    /*
     * 생성 상태는 항상 DRAFT다 — 만들자마자 공개되는 경로를 두지 않는다. 일시가 없으므로
     * eventPhase는 NONE, 폼이 없으므로 receiptStatus는 null이다.
     */
    @Test
    void createEventReturns201WithDraftStatus() throws Exception {
        mockMvc.perform(
                        authorized(post(EVENTS), managerToken)
                                .content(eventBody("RECRUIT", "2026 신입 모집", null, null, null)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.eventId").isNumber())
                .andExpect(jsonPath("$.data.eventClsfCd").value("RECRUIT"))
                .andExpect(jsonPath("$.data.eventClsfNm").value("모집"))
                .andExpect(jsonPath("$.data.eventTtl").value("2026 신입 모집"))
                .andExpect(jsonPath("$.data.mtxtCn").value("# 모집 요강"))
                .andExpect(jsonPath("$.data.eventSttsCd").value("DRAFT"))
                .andExpect(jsonPath("$.data.eventPhase").value("NONE"))
                .andExpect(jsonPath("$.data.formId").isEmpty())
                .andExpect(jsonPath("$.data.receiptStatus").isEmpty())
                .andExpect(jsonPath("$.data.confirmedCount").value(0));
    }

    @Test
    void createEventWithUnknownClassificationReturns404() throws Exception {
        mockMvc.perform(
                        authorized(post(EVENTS), managerToken)
                                .content(eventBody("UNKNOWN", "분류 없는 행사", null, null, null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_CLASSIFICATION_NOT_FOUND"));
    }

    @Test
    void createEventWithoutTitleReturnsValidationFailed() throws Exception {
        mockMvc.perform(
                        authorized(post(EVENTS), managerToken)
                                .content(
                                        """
                                        {"eventClsfCd": "RECRUIT", "eventTtl": "", "mtxtCn": "본문"}
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /*
     * 본문 상한(10만 자). DB는 TEXT라 제한하지 않으므로 저장 API가 최종 방어선이다 —
     * 경계(딱 10만 자)는 통과하고 한 자만 넘어도 413이어야 한다.
     */
    @Test
    void createEventContentAtLimitPassesButOverLimitReturns413() throws Exception {
        mockMvc.perform(
                        authorized(post(EVENTS), managerToken)
                                .content(contentBody("가".repeat(100_000))))
                .andExpect(status().isCreated());

        mockMvc.perform(
                        authorized(post(EVENTS), managerToken)
                                .content(contentBody("가".repeat(100_001))))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("EVENT_CONTENT_TOO_LARGE"));
    }

    /* ── 목록 ─────────────────────────────────────────────── */

    /*
     * 필터 네 조합을 한 테스트에서 확인한다 — 확인하려는 것("둘 다 주면 AND")은 조합 간
     * 비교라 한 자리에 있어야 한다 (FormControllerTest 선례).
     */
    @Test
    void getEventsAppliesClassificationAndStatusFiltersWithAnd() throws Exception {
        Long publishedRecruit = createEvent("RECRUIT", "게시된 모집");
        changeStatus(publishedRecruit, "PUBLISH").andExpect(status().isOk());
        Long draftRecruit = createEvent("RECRUIT", "작성 중 모집");
        Long publishedSeminar = createEvent("SEMINAR", "게시된 세미나");
        changeStatus(publishedSeminar, "PUBLISH").andExpect(status().isOk());

        mockMvc.perform(authorized(get(EVENTS), managerToken))
                .andExpect(jsonPath("$.data.length()").value(3));

        mockMvc.perform(authorized(get(EVENTS + "?eventSttsCd=PUBLISHED"), managerToken))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(
                        jsonPath(
                                "$.data[*].eventId",
                                Matchers.hasItems(
                                        publishedRecruit.intValue(), publishedSeminar.intValue())));

        mockMvc.perform(authorized(get(EVENTS + "?eventClsfCd=RECRUIT"), managerToken))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(
                        jsonPath(
                                "$.data[*].eventId",
                                Matchers.hasItems(
                                        publishedRecruit.intValue(), draftRecruit.intValue())));

        mockMvc.perform(
                        authorized(
                                get(EVENTS + "?eventClsfCd=RECRUIT&eventSttsCd=PUBLISHED"),
                                managerToken))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].eventId").value(publishedRecruit))
                .andExpect(jsonPath("$.data[0].eventClsfNm").value("모집"))
                // 목록에는 본문을 싣지 않는다 — 상세만 싣는다
                .andExpect(jsonPath("$.data[0].mtxtCn").doesNotExist());
    }

    /* ── eventPhase 파생 (D9) ─────────────────────────────── */

    /*
     * 진행 단계는 저장된 값이 아니라 행사 일시와 고정 시각(3/15 00:00 KST)의 비교다.
     * 네 갈래(NONE·UPCOMING·ONGOING·ENDED)를 한 자리에서 비교한다 — 파생 규칙은 갈래 간
     * 경계가 요점이라 나누면 규칙이 보이지 않는다.
     */
    @Test
    void eventPhaseIsDerivedFromEventPeriodAtReadTime() throws Exception {
        Long noDates = createEvent("EVENT", "일시 미정 공지");
        Long upcoming =
                createEventWithPeriod(
                        "EVENT", "예정 행사", "2026-04-01T18:00:00+09:00", "2026-04-01T21:00:00+09:00");
        Long ongoing =
                createEventWithPeriod(
                        "EVENT",
                        "진행 중 행사",
                        "2026-03-10T18:00:00+09:00",
                        "2026-03-20T21:00:00+09:00");
        Long ended =
                createEventWithPeriod(
                        "EVENT", "끝난 행사", "2026-03-01T18:00:00+09:00", "2026-03-01T21:00:00+09:00");
        // 종료 일시 없이 시작만 지난 행사는 끝을 알 수 없으므로 진행 중이다
        Long openEnded =
                createEventWithPeriod("EVENT", "시작만 있는 행사", "2026-03-10T18:00:00+09:00", null);

        assertPhase(noDates, "NONE");
        assertPhase(upcoming, "UPCOMING");
        assertPhase(ongoing, "ONGOING");
        assertPhase(ended, "ENDED");
        assertPhase(openEnded, "ONGOING");
    }

    /* ── receiptStatus 파생 (D3) ──────────────────────────── */

    /*
     * 모집 상태는 행사에 저장되지 않고 연결된 폼의 FormReceiptPolicy 판정 그대로다.
     * 접수 기간(3/1~3/31)이 고정 시각을 감싸므로 OPEN 폼은 ACCEPTING이어야 한다.
     */
    @Test
    void linkedFormReceiptStatusIsDerivedFromFormReceiptPolicy() throws Exception {
        Long formId =
                createOpenForm("모집 지원서", "2026-03-01T00:00:00+09:00", "2026-03-31T00:00:00+09:00");
        Long eventId = createEventLinkedTo("RECRUIT", "모집 행사", formId);

        mockMvc.perform(authorized(get(EVENTS + "/" + eventId), managerToken))
                .andExpect(jsonPath("$.data.formId").value(formId))
                .andExpect(jsonPath("$.data.receiptStatus").value("ACCEPTING"));
    }

    /* ── N+1 회귀 방지 ───────────────────────────────────── */

    /*
     * 목록 쿼리는 행사(분류·폼 페치 포함) 1 + 확정 참가자 집계 1로 2회다. 행사가 몇 건이든
     * 참가자가 몇 명이든 이 수는 그대로다 (DB-13 · 폼 목록의 3회 선례).
     */
    @Test
    void getEventsRunsTwoQueriesRegardlessOfEventAndParticipantCount() throws Exception {
        for (int index = 0; index < 3; index++) {
            Long eventId = createEvent("EVENT", "집계 행사 " + index);
            saveConfirmedParticipant(eventId, "2026010" + index);
        }
        entityManager.flush();
        entityManager.clear();

        Statistics statistics =
                entityManager
                        .getEntityManagerFactory()
                        .unwrap(SessionFactory.class)
                        .getStatistics();
        statistics.clear();

        List<EventSummaryResponse> events = eventService.getEvents(null, null);

        assertThat(events).hasSize(3);
        assertThat(events).allSatisfy(event -> assertThat(event.confirmedCount()).isEqualTo(1));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }

    /*
     * confirmedCount는 확정만 센다 — 대기·취소를 세면 화면의 "확정 N/정원"이 부푼다.
     */
    @Test
    void confirmedCountExcludesWaitlistedAndCancelled() throws Exception {
        Long eventId = createEvent("EVENT", "참가자 집계 행사");
        saveParticipant(eventId, "20260011", EventParticipantStatus.CONFIRMED);
        saveParticipant(eventId, "20260012", EventParticipantStatus.WAITLISTED);
        saveParticipant(eventId, "20260013", EventParticipantStatus.CANCELLED);

        mockMvc.perform(authorized(get(EVENTS + "/" + eventId), managerToken))
                .andExpect(jsonPath("$.data.confirmedCount").value(1));
    }

    /* ── 게시 상태 전이 ──────────────────────────────────── */

    /*
     * 허용 전이 네 가지를 한 행사로 차례로 밟는다 — DRAFT→PUBLISHED(게시)→ARCHIVED(보관)
     * →PUBLISHED(재공개)→DRAFT(철회). 전이표의 허용 칸 전부다.
     */
    @Test
    void allowedTransitionsFollowTheTable() throws Exception {
        Long eventId = createEvent("EVENT", "전이 대상 행사");

        changeStatus(eventId, "PUBLISH")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eventSttsCd").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.mdfcnDt").isNotEmpty());
        changeStatus(eventId, "ARCHIVE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eventSttsCd").value("ARCHIVED"));
        changeStatus(eventId, "REPUBLISH")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eventSttsCd").value("PUBLISHED"));
        changeStatus(eventId, "RETRACT")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eventSttsCd").value("DRAFT"));
    }

    /*
     * 전이표에 없는 조합은 전부 같은 코드로 거절된다. 각 상태의 대표 거부 조합을 한 자리에서
     * 비교한다 (FormControllerTest.transitionsOutsideTheTableReturn400 선례).
     */
    @Test
    void transitionsOutsideTheTableReturn400() throws Exception {
        Long draftEvent = createEvent("EVENT", "작성 중 행사");
        Long publishedEvent = createEvent("EVENT", "게시된 행사");
        changeStatus(publishedEvent, "PUBLISH").andExpect(status().isOk());
        Long archivedEvent = createEvent("EVENT", "보관된 행사");
        changeStatus(archivedEvent, "PUBLISH").andExpect(status().isOk());
        changeStatus(archivedEvent, "ARCHIVE").andExpect(status().isOk());

        assertTransitionRejected(draftEvent, "RETRACT"); // 게시된 적이 없어 철회할 것이 없다
        assertTransitionRejected(draftEvent, "ARCHIVE"); // 게시를 거치지 않고는 보관할 수 없다
        assertTransitionRejected(draftEvent, "REPUBLISH"); // 보관된 적이 없다
        assertTransitionRejected(publishedEvent, "PUBLISH"); // 이미 게시됨
        assertTransitionRejected(publishedEvent, "REPUBLISH"); // 재공개는 보관에서만
        assertTransitionRejected(archivedEvent, "ARCHIVE"); // 이미 보관됨
        assertTransitionRejected(archivedEvent, "PUBLISH"); // 보관에서 꺼내는 길은 REPUBLISH다
    }

    /* ── 폼 연결 규칙 (D11) ──────────────────────────────── */

    // 한 폼은 최대 한 행사에만 전속된다 — 두 번째 연결은 생성이든 수정이든 같은 409다
    @Test
    void linkingFormAlreadyOwnedByAnotherEventReturns409() throws Exception {
        Long formId = createOpenForm("전속 폼", null, null);
        createEventLinkedTo("RECRUIT", "먼저 연결한 행사", formId);

        mockMvc.perform(
                        authorized(post(EVENTS), managerToken)
                                .content(eventBody("RECRUIT", "나중에 연결한 행사", formId, null, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FORM_ALREADY_LINKED"));

        Long otherEvent = createEvent("RECRUIT", "폼 없는 행사");
        mockMvc.perform(
                        authorized(put(EVENTS + "/" + otherEvent), managerToken)
                                .content(eventBody("RECRUIT", "폼 없는 행사", formId, null, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FORM_ALREADY_LINKED"));
    }

    /*
     * 신청(제출 이후 응답)이 발생하면 연결을 해제할 수 없다. 연결을 움직이지 않는 수정은
     * 신청이 몇 건이든 통과해야 한다 — 그래야 신청이 시작된 행사도 오타를 고칠 수 있다.
     */
    @Test
    void unlinkingFormAfterSubmittedResponseReturns409ButKeepingItPasses() throws Exception {
        Long formId = createOpenForm("신청 폼", null, null);
        Long eventId = createEventLinkedTo("RECRUIT", "신청 시작된 행사", formId);
        saveSubmittedResponse(formId, "20260021");

        mockMvc.perform(
                        authorized(put(EVENTS + "/" + eventId), managerToken)
                                .content(eventBody("RECRUIT", "제목만 고친 행사", formId, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eventTtl").value("제목만 고친 행사"))
                .andExpect(jsonPath("$.data.formId").value(formId));

        mockMvc.perform(
                        authorized(put(EVENTS + "/" + eventId), managerToken)
                                .content(eventBody("RECRUIT", "연결을 끊은 행사", null, null, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_FORM_IN_USE"));
    }

    // 참가자(수동 등록 포함)가 있어도 연결은 움직일 수 없다 — 응답과 참가자 어느 쪽으로든 신청은 신청이다
    @Test
    void unlinkingFormAfterParticipantReturns409() throws Exception {
        Long formId = createOpenForm("참가자 있는 행사의 폼", null, null);
        Long eventId = createEventLinkedTo("EVENT", "참가자 있는 행사", formId);
        saveConfirmedParticipant(eventId, "20260031");

        mockMvc.perform(
                        authorized(put(EVENTS + "/" + eventId), managerToken)
                                .content(eventBody("EVENT", "참가자 있는 행사", null, null, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_FORM_IN_USE"));
    }

    /*
     * 임시저장(DRAFT) 응답만 있는 폼은 아직 신청이 없다 — 기준은 제출 이상
     * (ResponseStatus.submittedOrLater)이다. 문항 식별자 보호(DRAFT 포함)와 기준이 다르다.
     */
    @Test
    void unlinkingFormWithOnlyDraftResponsesPasses() throws Exception {
        Long formId = createOpenForm("초안만 있는 폼", null, null);
        Long eventId = createEventLinkedTo("RECRUIT", "초안만 있는 행사", formId);
        saveDraftResponse(formId, "20260041");

        mockMvc.perform(
                        authorized(put(EVENTS + "/" + eventId), managerToken)
                                .content(eventBody("RECRUIT", "연결을 끊은 행사", null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.formId").isEmpty());
    }

    @Test
    void createEventWithUnknownFormReturns404() throws Exception {
        mockMvc.perform(
                        authorized(post(EVENTS), managerToken)
                                .content(eventBody("RECRUIT", "없는 폼을 연결한 행사", 999999L, null, null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /* ── 수정 ─────────────────────────────────────────────── */

    // PUT은 상태 필드 자체가 없다 — 게시된 행사를 수정해도 게시 상태가 유지되어야 한다
    @Test
    void updateEventKeepsStatusAndReplacesContent() throws Exception {
        Long eventId = createEvent("RECRUIT", "수정 대상 행사");
        changeStatus(eventId, "PUBLISH").andExpect(status().isOk());

        mockMvc.perform(
                        authorized(put(EVENTS + "/" + eventId), managerToken)
                                .content(
                                        eventBody(
                                                "SEMINAR",
                                                "분류를 옮긴 행사",
                                                null,
                                                "2026-03-10T18:00:00+09:00",
                                                "2026-03-20T21:00:00+09:00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eventClsfCd").value("SEMINAR"))
                .andExpect(jsonPath("$.data.eventClsfNm").value("세미나"))
                .andExpect(jsonPath("$.data.eventTtl").value("분류를 옮긴 행사"))
                .andExpect(jsonPath("$.data.eventSttsCd").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.eventPhase").value("ONGOING"));
    }

    @Test
    void updateUnknownEventReturns404() throws Exception {
        mockMvc.perform(
                        authorized(put(EVENTS + "/999999"), managerToken)
                                .content(eventBody("RECRUIT", "없는 행사", null, null, null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
    }

    /* ── 삭제 (D9) ───────────────────────────────────────── */

    @Test
    void deleteEventWithoutParticipantSucceeds() throws Exception {
        Long eventId = createEvent("EVENT", "지울 행사");

        mockMvc.perform(authorized(delete(EVENTS + "/" + eventId), managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(authorized(get(EVENTS + "/" + eventId), managerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
    }

    // 명단은 활동 이력으로 영구 보존된다(D16) — 참가자가 생긴 행사는 삭제가 아니라 보관이 경로다
    @Test
    void deleteEventWithParticipantReturns409() throws Exception {
        Long eventId = createEvent("EVENT", "참가자 있는 행사");
        saveParticipant(eventId, "20260051", EventParticipantStatus.CANCELLED);

        mockMvc.perform(authorized(delete(EVENTS + "/" + eventId), managerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_HAS_PARTICIPANT"));
    }

    /* ── 인증·인가 ───────────────────────────────────────── */

    @Test
    void requestsWithoutTokenReturn401() throws Exception {
        mockMvc.perform(get(EVENTS)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(EVENTS).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // 클래스 레벨 EVENT_MANAGE다 — 조회도 예외가 아니다
    @Test
    void requestsWithoutEventManageAreForbidden() throws Exception {
        mockMvc.perform(authorized(get(EVENTS), outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(
                        authorized(post(EVENTS), outsiderToken)
                                .content(eventBody("RECRUIT", "권한 없는 행사", null, null, null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(
                        authorized(post(EVENTS + "/1/status"), outsiderToken)
                                .content("{\"action\": \"PUBLISH\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    /* ── 헬퍼 ─────────────────────────────────────────────── */

    private void assertPhase(Long eventId, String phase) throws Exception {
        mockMvc.perform(authorized(get(EVENTS + "/" + eventId), managerToken))
                .andExpect(jsonPath("$.data.eventPhase").value(phase));
    }

    private void assertTransitionRejected(Long eventId, String action) throws Exception {
        changeStatus(eventId, action)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("INVALID_EVENT_STATUS_TRANSITION"));
    }

    private org.springframework.test.web.servlet.ResultActions changeStatus(
            Long eventId, String action) throws Exception {
        return mockMvc.perform(
                authorized(post(EVENTS + "/" + eventId + "/status"), managerToken)
                        .content("{\"action\": \"" + action + "\"}"));
    }

    private String eventBody(
            String classificationCode, String title, Long formId, String beginAt, String endAt) {
        return """
               {
                 "eventClsfCd": "%s",
                 "eventTtl": "%s",
                 "mtxtCn": "# 모집 요강",
                 "formId": %s,
                 "eventBgngDt": %s,
                 "eventEndDt": %s,
                 "plcNm": "정보과학관 21203",
                 "ptcpLmtCnt": 40
               }
               """
                .formatted(
                        classificationCode,
                        title,
                        formId == null ? "null" : formId,
                        quoteOrNull(beginAt),
                        quoteOrNull(endAt));
    }

    private String contentBody(String content) {
        return """
               {"eventClsfCd": "EVENT", "eventTtl": "본문 상한 행사", "mtxtCn": "%s"}
               """
                .formatted(content);
    }

    private String quoteOrNull(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private Long createEvent(String classificationCode, String title) throws Exception {
        return createEventWithBody(eventBody(classificationCode, title, null, null, null));
    }

    private Long createEventWithPeriod(
            String classificationCode, String title, String beginAt, String endAt)
            throws Exception {
        return createEventWithBody(eventBody(classificationCode, title, null, beginAt, endAt));
    }

    private Long createEventLinkedTo(String classificationCode, String title, Long formId)
            throws Exception {
        return createEventWithBody(eventBody(classificationCode, title, formId, null, null));
    }

    private Long createEventWithBody(String body) throws Exception {
        String response =
                mockMvc.perform(authorized(post(EVENTS), managerToken).content(body))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.parse(response).read("$.data.eventId", Long.class);
    }

    /** 폼 API로 OPEN 폼을 만든다 — 국장 역할이 FORM_WRITE에도 닿는다 */
    private Long createOpenForm(String title, String beginAt, String endAt) throws Exception {
        String body =
                """
                {
                  "formTtlNm": "%s",
                  "formSttsCd": "OPEN",
                  "rcptBgngDt": %s,
                  "rcptEndDt": %s,
                  "qitemCpstCn": %s
                }
                """
                        .formatted(
                                title, quoteOrNull(beginAt), quoteOrNull(endAt), VALID_COMPOSITION);
        String response =
                mockMvc.perform(authorized(post("/v1/forms"), managerToken).content(body))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.parse(response).read("$.data.formId", Long.class);
    }

    private void saveSubmittedResponse(Long formId, String studentNumber) {
        FormEntity form = formRepository.findById(formId).orElseThrow();
        MemberEntity responder = saveMember(UUID.randomUUID(), studentNumber, "응답자");
        formResponseHistoryRepository.saveAndFlush(
                FormResponseHistoryEntity.createSubmitted(
                        form,
                        responder,
                        ResponseContent.of(Map.of("q1", "홍길동")),
                        Instant.parse("2026-03-10T12:00:00Z")));
    }

    private void saveDraftResponse(Long formId, String studentNumber) {
        FormEntity form = formRepository.findById(formId).orElseThrow();
        MemberEntity responder = saveMember(UUID.randomUUID(), studentNumber, "응답자");
        formResponseHistoryRepository.saveAndFlush(
                FormResponseHistoryEntity.createDraft(form, responder, null));
    }

    private void saveConfirmedParticipant(Long eventId, String studentNumber) {
        saveParticipant(eventId, studentNumber, EventParticipantStatus.CONFIRMED);
    }

    private void saveParticipant(
            Long eventId, String studentNumber, EventParticipantStatus status) {
        EventEntity event = eventRepository.findById(eventId).orElseThrow();
        MemberEntity participant = saveMember(UUID.randomUUID(), studentNumber, "참가자");
        eventParticipantRepository.saveAndFlush(
                EventParticipantEntity.register(event, participant, status, null, manager));
    }

    private MemberEntity saveMember(UUID authUserId, String studentNumber, String name) {
        return MemberFixture.save(
                memberRepository,
                memberGradeRepository,
                memberStatusRepository,
                authUserId,
                studentNumber,
                name,
                studentNumber + "@soongsil.ac.kr");
    }

    private static MockHttpServletRequestBuilder authorized(
            MockHttpServletRequestBuilder builder, UUID authUserId) {
        return builder.header("Authorization", "Bearer " + authUserId)
                .contentType(MediaType.APPLICATION_JSON);
    }

    @TestConfiguration
    static class StubJwtDecoderConfig {

        /*
         * eventPhase·receiptStatus가 주입된 Clock에서 오는지 확인해야 하므로 시각을 고정한다.
         * 시스템 시각을 그대로 쓰면 단계 파생 케이스가 달력에 따라 통과·실패를 오간다.
         */
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));
        }

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
