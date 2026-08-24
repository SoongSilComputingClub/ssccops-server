package org.sscc.ssccopsserver.domain.event.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
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
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent.Page;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent.QuestionItem;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.support.MemberFixture;

/*
 * 공개 행사 조회 API(ssccops#143) 통합 검증.
 *
 * **모든 요청에 Authorization 헤더가 없다.** 그것이 이 테스트의 요점이다 — permitAll이 실제로
 * 걸려 있는지는 필터체인을 통째로 태워 봐야만 확인되고, 여기서 토큰을 붙이면 SecurityConfig의
 * 규칙이 사라져도 테스트는 계속 초록으로 남는다.
 *
 * 표본은 API가 아니라 리포지토리로 직접 만든다. 행사를 만드는 API는 EVENT_MANAGE를 요구하는데,
 * 그 권한을 세우는 픽스처를 여기 두면 "익명"이라는 전제가 준비 단계에서 흐려진다.
 *
 * eventPhase·receiptStatus가 주입된 Clock에서 오므로 시각을 2026-03-15 00:00 KST로 고정한다
 * (EventControllerTest와 같은 기준 시각).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PublicEventControllerTest.FixedClockConfig.class)
@Transactional
class PublicEventControllerTest {

    /** 고정 기준 시각 (2026-03-15 00:00 KST) */
    private static final Instant NOW = Instant.parse("2026-03-14T15:00:00Z");

    private static final String PUBLIC_EVENTS = "/public/v1/events";

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private EventClassificationRepository eventClassificationRepository;
    @Autowired private EventParticipantRepository eventParticipantRepository;
    @Autowired private FormRepository formRepository;

    private MemberEntity creator;
    private int studentNumberSeq = 1;

    @BeforeEach
    void setUp() {
        creator = saveMember("행사운영자");
    }

    /* ── 익명 접근 · PUBLISHED 필터 ───────────────────────── */

    /*
     * 토큰 없이 200이고, 나오는 것은 게시된 행사뿐이다. 작성 중·보관된 행사는 목록에서 빠지는
     * 것이 아니라 익명에게는 존재하지 않는다.
     */
    @Test
    void publicListIsOpenToAnonymousAndShowsOnlyPublishedEvents() throws Exception {
        Long published = saveEvent("RECRUIT", "게시된 모집", true);
        saveEvent("RECRUIT", "작성 중 모집", false);
        Long archived = saveEvent("SEMINAR", "보관된 세미나", true);
        archive(archived);

        mockMvc.perform(get(PUBLIC_EVENTS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].eventId").value(published))
                .andExpect(jsonPath("$.data[0].eventTtl").value("게시된 모집"))
                .andExpect(jsonPath("$.data[0].eventClsfNm").value("모집"));
    }

    // 분류 필터. 상태 필터는 계약에 없다 — 목록은 언제나 PUBLISHED뿐이다
    @Test
    void publicListFiltersByClassification() throws Exception {
        Long recruit = saveEvent("RECRUIT", "게시된 모집", true);
        Long seminar = saveEvent("SEMINAR", "게시된 세미나", true);

        mockMvc.perform(get(PUBLIC_EVENTS + "?eventClsfCd=RECRUIT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].eventId").value(recruit));

        mockMvc.perform(get(PUBLIC_EVENTS + "?eventClsfCd=SEMINAR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].eventId").value(seminar));
    }

    /*
     * 목록 응답에 무엇이 없는지를 JSON 키로 못 박는다 — 운영자용 DTO를 나눈 이유가 이것이라,
     * 필드가 하나 새어 나가면 여기서 걸려야 한다.
     */
    @Test
    void publicListOmitsOperatorOnlyFields() throws Exception {
        saveEvent("RECRUIT", "게시된 모집", true);

        mockMvc.perform(get(PUBLIC_EVENTS))
                .andExpect(status().isOk())
                // 본문은 상세에만 싣는다
                .andExpect(jsonPath("$.data[0].mtxtCn").doesNotExist())
                // 저장 상태·작성자·연결 폼 식별자·운영 타임스탬프는 공개 계약에 없다
                .andExpect(jsonPath("$.data[0].eventSttsCd").doesNotExist())
                .andExpect(jsonPath("$.data[0].creatrMbrId").doesNotExist())
                .andExpect(jsonPath("$.data[0].formId").doesNotExist())
                .andExpect(jsonPath("$.data[0].confirmedCount").doesNotExist())
                .andExpect(jsonPath("$.data[0].crtDt").doesNotExist())
                .andExpect(jsonPath("$.data[0].mdfcnDt").doesNotExist());
    }

    // 폼 없는 공지의 모집 배지는 null이다 (D3) — 행사에는 모집 기간이 없다
    @Test
    void eventWithoutFormHasNullReceiptStatus() throws Exception {
        saveEvent("EVENT", "폼 없는 공지", true);

        mockMvc.perform(get(PUBLIC_EVENTS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].receiptStatus").isEmpty())
                .andExpect(jsonPath("$.data[0].eventPhase").value("NONE"));
    }

    /* ── 상세 ─────────────────────────────────────────────── */

    /*
     * 상세는 본문(md 원문)과 "확정 인원/정원"까지다 (D12). 확정만 세므로 대기·취소는 숫자에
     * 들어가지 않는다 — 방문자가 남은 자리를 잘못 읽지 않게 하는 자리다.
     */
    @Test
    void publicDetailReturnsMarkdownAndConfirmedCountOnly() throws Exception {
        Long eventId = saveEventWithFormAndLimit("RECRUIT", "게시된 모집", 20);
        saveParticipant(eventId, EventParticipantStatus.CONFIRMED);
        saveParticipant(eventId, EventParticipantStatus.CONFIRMED);
        saveParticipant(eventId, EventParticipantStatus.WAITLISTED);
        saveParticipant(eventId, EventParticipantStatus.CANCELLED);

        mockMvc.perform(get(PUBLIC_EVENTS + "/" + eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eventId").value(eventId))
                .andExpect(jsonPath("$.data.mtxtCn").value("# 모집 요강"))
                .andExpect(jsonPath("$.data.ptcpLmtCnt").value(20))
                .andExpect(jsonPath("$.data.confirmedCount").value(2))
                // 연결된 폼이 접수 중이면 모집 배지가 ACCEPTING이다 (FormReceiptPolicy)
                .andExpect(jsonPath("$.data.receiptStatus").value("ACCEPTING"))
                // 상세에도 운영자용 필드는 없다 — 참가자 명단도 폼 문항도 싣지 않는다
                .andExpect(jsonPath("$.data.eventSttsCd").doesNotExist())
                .andExpect(jsonPath("$.data.creatrMbrId").doesNotExist())
                .andExpect(jsonPath("$.data.participants").doesNotExist())
                .andExpect(jsonPath("$.data.qitemCpstCn").doesNotExist());
    }

    /*
     * 신청 버튼을 누른 방문자를 연결 폼으로 보내려면 공개 앱이 어느 폼인지 알아야 한다 (#165).
     * 식별자만 내리고 문항은 여전히 인증 경로(GET /v1/forms/{formId}/public)에서만 나온다 —
     * 그래서 여기서 qitemCpstCn이 없다는 위 단언과 함께 봐야 의미가 산다.
     */
    @Test
    void publicDetailCarriesLinkedFormIdSoThatApplyFlowCanContinue() throws Exception {
        Long eventId = saveEventWithFormAndLimit("RECRUIT", "게시된 모집", 20);

        mockMvc.perform(get(PUBLIC_EVENTS + "/" + eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.formId").isNumber())
                .andExpect(jsonPath("$.data.qitemCpstCn").doesNotExist());
    }

    // 폼 없는 공지 성격 행사는 formId가 null이다 — 화면은 이때 신청 버튼을 내린다
    @Test
    void publicDetailHasNullFormIdWhenNoFormIsLinked() throws Exception {
        Long eventId = saveEvent("EVENT", "폼 없는 공지", true);

        mockMvc.perform(get(PUBLIC_EVENTS + "/" + eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.formId").doesNotExist())
                .andExpect(jsonPath("$.data.receiptStatus").doesNotExist());
    }

    /*
     * 게시되지 않은 행사는 403이 아니라 404다 — 존재를 감춘다. 없는 행사와 같은 코드인 것도
     * 같은 이유이고, 코드를 나누면 "그 번호에 무엇인가 있다"가 새어 나간다.
     */
    @Test
    void draftArchivedAndUnknownEventsAllReturn404() throws Exception {
        Long draft = saveEvent("RECRUIT", "작성 중 모집", false);
        Long archived = saveEvent("SEMINAR", "보관된 세미나", true);
        archive(archived);

        assertHidden(draft);
        assertHidden(archived);
        assertHidden(999999L);
    }

    private void assertHidden(Long eventId) throws Exception {
        mockMvc.perform(get(PUBLIC_EVENTS + "/" + eventId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
    }

    /* ── 픽스처 ───────────────────────────────────────────── */

    private Long saveEvent(String classificationCode, String title, boolean published) {
        return saveEvent(classificationCode, title, published, null, null);
    }

    /** 접수 중인 폼과 정원을 붙인 게시 행사 — 상세의 receiptStatus·정원 표본이다 */
    private Long saveEventWithFormAndLimit(
            String classificationCode, String title, Integer participantLimitCount) {
        return saveEvent(classificationCode, title, true, saveOpenForm(), participantLimitCount);
    }

    private Long saveEvent(
            String classificationCode,
            String title,
            boolean published,
            FormEntity form,
            Integer participantLimitCount) {

        EventClassificationEntity classification =
                eventClassificationRepository.findById(classificationCode).orElseThrow();
        EventEntity event =
                EventEntity.create(
                        classification,
                        creator,
                        title,
                        "# 모집 요강",
                        "https://cdn.sscc.org/thumb.png",
                        form,
                        null,
                        null,
                        "학생회관 2층",
                        participantLimitCount);
        if (published) {
            event.changeStatus(EventStatusAction.PUBLISH);
        }
        return eventRepository.saveAndFlush(event).getId();
    }

    private void archive(Long eventId) {
        EventEntity event = eventRepository.findById(eventId).orElseThrow();
        event.changeStatus(EventStatusAction.ARCHIVE);
        eventRepository.flush();
    }

    /** 접수 기간이 없는 OPEN 폼 — FormReceiptPolicy가 ACCEPTING으로 판정한다 */
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
                FormEntity.create(creator, "신청 폼", composition, null, null, FormStatus.OPEN));
    }

    /*
     * 등록 팩토리는 취소 상태를 받지 않으므로(ssccops#146 — 취소는 확정된 참가자에게 일어나는
     * 일이다) 취소 표본은 확정으로 만든 뒤 전이시킨다. 테스트를 위해 규칙에 예외를 두지 않는다.
     */
    private void saveParticipant(Long eventId, EventParticipantStatus status) {
        EventEntity event = eventRepository.findById(eventId).orElseThrow();
        boolean cancel = status == EventParticipantStatus.CANCELLED;
        EventParticipantEntity participant =
                EventParticipantEntity.register(
                        event,
                        saveMember("참가자"),
                        cancel ? EventParticipantStatus.CONFIRMED : status,
                        null,
                        creator);
        eventParticipantRepository.saveAndFlush(participant);
        if (cancel) {
            participant.changeStatus(EventParticipantStatus.CANCELLED);
            eventParticipantRepository.flush();
        }
    }

    private MemberEntity saveMember(String name) {
        String studentNumber = "2026%04d".formatted(studentNumberSeq++);
        return MemberFixture.save(
                memberRepository,
                memberGradeRepository,
                memberStatusRepository,
                UUID.randomUUID(),
                studentNumber,
                name,
                studentNumber + "@soongsil.ac.kr");
    }

    @TestConfiguration
    static class FixedClockConfig {

        /*
         * eventPhase·receiptStatus가 주입된 Clock에서 오므로 시각을 고정한다. 시스템 시각을
         * 그대로 쓰면 파생 값 검증이 달력에 따라 통과·실패를 오간다.
         */
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));
        }

        /*
         * 이 테스트는 토큰을 붙이지 않으므로 디코더가 호출될 일이 없지만, 실제 JWKS URI를 향한
         * 빈이 컨텍스트에 남아 있으면 나중에 인증 요청을 하나 더하는 순간 네트워크를 타게 된다.
         */
        @Bean
        @Primary
        JwtDecoder jwtDecoder() {
            return token ->
                    Jwt.withTokenValue(token)
                            .header("alg", "none")
                            .subject(token)
                            .issuedAt(Instant.now())
                            .expiresAt(Instant.now().plusSeconds(60))
                            .build();
        }
    }
}
