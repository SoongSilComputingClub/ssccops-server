package org.sscc.ssccopsserver.domain.event.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

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
import org.sscc.ssccopsserver.domain.event.code.EventStatusAction;
import org.sscc.ssccopsserver.domain.event.dto.MyApplicationResponse;
import org.sscc.ssccopsserver.domain.event.entity.EventClassificationEntity;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.event.entity.EventParticipantEntity;
import org.sscc.ssccopsserver.domain.event.repository.EventClassificationRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventParticipantRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventRepository;
import org.sscc.ssccopsserver.domain.event.service.MyApplicationService;
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
import org.sscc.ssccopsserver.domain.member.code.MemberStatusCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.support.MemberFixture;

/*
 * 내 신청 현황 조회 API(ssccops#145) 통합 검증.
 *
 * 표본(행사·폼·응답·명단)은 리포지토리로 직접 만든다 — 확인하려는 것은 파생 규칙과 범위이지
 * 표본을 만드는 운영 API가 아니고, 그쪽을 태우면 이 테스트가 EVENT_MANAGE 권한 픽스처까지
 * 짊어진다(EventParticipationControllerTest와 같은 판단). 이 API 자체는 권한을 요구하지 않아
 * 신청자에게 역할이 필요 없다는 것이 이 테스트의 픽스처가 가벼운 이유다.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MyApplicationControllerTest.StubJwtDecoderConfig.class)
@Transactional
class MyApplicationControllerTest {

    private static final String MY_APPLICATIONS = "/v1/events/my-applications";

    @PersistenceContext private EntityManager entityManager;

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private EventClassificationRepository eventClassificationRepository;
    @Autowired private EventParticipantRepository eventParticipantRepository;
    @Autowired private FormRepository formRepository;
    @Autowired private FormResponseHistoryRepository formResponseHistoryRepository;
    @Autowired private MyApplicationService myApplicationService;

    private UUID applicantToken;
    private MemberEntity applicant;
    private MemberEntity other;
    private int studentNumberSeq = 1;

    @BeforeEach
    void setUp() {
        applicantToken = UUID.randomUUID();
        applicant = saveMember(applicantToken, "신청자");
        other = saveMember(UUID.randomUUID(), "다른신청자");
    }

    /* ── 파생 규칙 ─────────────────────────────────────────── */

    /*
     * 명단 행이 응답 상태를 이긴다. 참가자 등록은 심사가 끝난 뒤에 일어나므로(#158) 명단이
     * 있다는 것은 그 신청의 심사가 이미 끝났다는 뜻이다 — 응답 상태를 우선하면 확정된 사람에게
     * "승인됨"만 보인다.
     */
    @Test
    void participantStatusWinsOverResponseStatus() throws Exception {
        FormEntity form = saveOpenForm();
        Long eventId = savePublishedEvent("EVENT", "확정 행사", form);
        saveResponse(form, applicant, ResponseStatus.ACCEPTED, "2026-03-10T12:00:00Z");
        Long participantId = saveParticipant(eventId, applicant, EventParticipantStatus.CONFIRMED);

        mockMvc.perform(authorized(get(MY_APPLICATIONS), applicantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].eventId").value(eventId))
                .andExpect(jsonPath("$.data[0].eventTtl").value("확정 행사"))
                .andExpect(jsonPath("$.data[0].eventClsfNm").value("행사"))
                .andExpect(jsonPath("$.data[0].plcNm").value("학생회관"))
                .andExpect(jsonPath("$.data[0].applicationStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.data[0].eventPtcpId").value(participantId))
                .andExpect(jsonPath("$.data[0].formRspnsId").isNumber())
                .andExpect(jsonPath("$.data[0].submittedAt").exists());
    }

    /** 대기도 명단 상태다. **순번은 어디에도 실리지 않는다** (D5 — 신청자에게 비공개) */
    @Test
    void waitlistedParticipantIsReportedWithoutPosition() throws Exception {
        FormEntity form = saveOpenForm();
        Long eventId = savePublishedEvent("EVENT", "대기 행사", form);
        saveResponse(form, applicant, ResponseStatus.ACCEPTED, "2026-03-10T12:00:00Z");
        saveParticipant(eventId, applicant, EventParticipantStatus.WAITLISTED);

        mockMvc.perform(authorized(get(MY_APPLICATIONS), applicantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].applicationStatus").value("WAITLISTED"))
                .andExpect(jsonPath("$.data[0].waitlistPosition").doesNotExist())
                .andExpect(jsonPath("$.data[0].ptcpSeq").doesNotExist());
    }

    /** 확정 후 취소도 신청자가 봐야 할 결과다 — 명단은 영구 보존이라 행이 남아 있다(D16) */
    @Test
    void cancelledParticipantIsReported() throws Exception {
        FormEntity form = saveOpenForm();
        Long eventId = savePublishedEvent("EVENT", "취소 행사", form);
        saveResponse(form, applicant, ResponseStatus.ACCEPTED, "2026-03-10T12:00:00Z");
        saveParticipant(eventId, applicant, EventParticipantStatus.CANCELLED);

        mockMvc.perform(authorized(get(MY_APPLICATIONS), applicantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].applicationStatus").value("CANCELLED"));
    }

    /*
     * 명단 행이 없으면 응답 상태를 쓴다. 수정요청(CHANGES_REQUESTED)은 웹과 합의된 여섯 어휘에
     * 없어 SUBMITTED로 접힌다 — 둘 다 "심사가 끝나지 않았다"이다.
     */
    @Test
    void responseStatusIsUsedWhenNotOnRoster() throws Exception {
        FormEntity submitted = saveOpenForm();
        savePublishedEvent("EVENT", "제출 행사", submitted);
        saveResponse(submitted, applicant, ResponseStatus.SUBMITTED, "2026-03-10T12:00:00Z");

        FormEntity accepted = saveOpenForm();
        savePublishedEvent("EVENT", "승인 행사", accepted);
        saveResponse(accepted, applicant, ResponseStatus.ACCEPTED, "2026-03-09T12:00:00Z");

        FormEntity rejected = saveOpenForm();
        savePublishedEvent("EVENT", "반려 행사", rejected);
        saveResponse(rejected, applicant, ResponseStatus.REJECTED, "2026-03-08T12:00:00Z");

        FormEntity changesRequested = saveOpenForm();
        savePublishedEvent("EVENT", "수정요청 행사", changesRequested);
        saveResponse(
                changesRequested,
                applicant,
                ResponseStatus.CHANGES_REQUESTED,
                "2026-03-07T12:00:00Z");

        mockMvc.perform(authorized(get(MY_APPLICATIONS), applicantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[0].applicationStatus").value("SUBMITTED"))
                .andExpect(jsonPath("$.data[0].eventPtcpId").isEmpty())
                .andExpect(jsonPath("$.data[1].applicationStatus").value("ACCEPTED"))
                .andExpect(jsonPath("$.data[2].applicationStatus").value("REJECTED"))
                .andExpect(jsonPath("$.data[3].applicationStatus").value("SUBMITTED"));
    }

    /* ── 범위 ─────────────────────────────────────────────── */

    /** 제출 전 초안은 신청이 아니다 — submittedOrLater()가 거른다 */
    @Test
    void draftResponseIsExcluded() throws Exception {
        FormEntity form = saveOpenForm();
        savePublishedEvent("EVENT", "초안만 있는 행사", form);
        formResponseHistoryRepository.saveAndFlush(
                FormResponseHistoryEntity.createDraft(form, applicant, null));

        mockMvc.perform(authorized(get(MY_APPLICATIONS), applicantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    /** 남의 신청은 섞이지 않는다 — 대상은 언제나 인증 주체 본인이다 */
    @Test
    void otherMembersApplicationIsNotIncluded() throws Exception {
        FormEntity form = saveOpenForm();
        Long eventId = savePublishedEvent("EVENT", "남의 신청 행사", form);
        saveResponse(form, other, ResponseStatus.ACCEPTED, "2026-03-10T12:00:00Z");
        saveParticipant(eventId, other, EventParticipantStatus.CONFIRMED);

        mockMvc.perform(authorized(get(MY_APPLICATIONS), applicantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    /*
     * 행사에 연결되지 않은 폼의 응답은 신청이 아니다. 지원서·설문 응답까지 실으면 '내 신청'이
     * '내 응답'이 되고, 그것은 이미 다른 API(#143)가 폼 단위로 하는 일이다.
     */
    @Test
    void responseToFormWithoutEventIsExcluded() throws Exception {
        FormEntity standaloneForm = saveOpenForm();
        saveResponse(standaloneForm, applicant, ResponseStatus.SUBMITTED, "2026-03-10T12:00:00Z");

        mockMvc.perform(authorized(get(MY_APPLICATIONS), applicantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    /*
     * 행사가 보관·작성 중이어도 내 신청 이력은 보인다. 공개 목록(#156)이 PUBLISHED만 내보내는
     * 것과 다른 문제다 — 그쪽은 남에게 보이지 않게 하는 것이고 이쪽은 내가 실제로 낸 신청이다.
     */
    @Test
    void applicationsToArchivedOrDraftEventsAreStillListed() throws Exception {
        FormEntity archivedForm = saveOpenForm();
        Long archivedEventId = savePublishedEvent("EVENT", "보관된 행사", archivedForm);
        archive(archivedEventId);
        saveResponse(archivedForm, applicant, ResponseStatus.SUBMITTED, "2026-03-10T12:00:00Z");

        FormEntity draftForm = saveOpenForm();
        saveDraftEvent("EVENT", "작성 중 행사", draftForm);
        saveResponse(draftForm, applicant, ResponseStatus.SUBMITTED, "2026-03-09T12:00:00Z");

        mockMvc.perform(authorized(get(MY_APPLICATIONS), applicantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].eventTtl").value("보관된 행사"))
                .andExpect(jsonPath("$.data[1].eventTtl").value("작성 중 행사"));
    }

    /** 신청이 없으면 204가 아니라 빈 배열이다 — 공통 응답 봉투에 예외를 만들지 않는다 */
    @Test
    void returnsEmptyArrayWhenNoApplication() throws Exception {
        mockMvc.perform(authorized(get(MY_APPLICATIONS), applicantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    /* ── 정렬 ─────────────────────────────────────────────── */

    /*
     * 최신 신청 순(제출 일시 내림차순). 같은 시각은 식별자 내림차순으로 끊어 다시 열어도
     * 순서가 흔들리지 않게 한다.
     */
    @Test
    void sortedByLatestSubmissionThenIdDescending() throws Exception {
        FormEntity oldest = saveOpenForm();
        savePublishedEvent("EVENT", "가장 오래된 신청", oldest);
        saveResponse(oldest, applicant, ResponseStatus.SUBMITTED, "2026-03-01T12:00:00Z");

        FormEntity tieFirst = saveOpenForm();
        savePublishedEvent("EVENT", "동시각 먼저 만든 신청", tieFirst);
        saveResponse(tieFirst, applicant, ResponseStatus.SUBMITTED, "2026-03-05T12:00:00Z");

        FormEntity tieSecond = saveOpenForm();
        savePublishedEvent("EVENT", "동시각 나중 만든 신청", tieSecond);
        saveResponse(tieSecond, applicant, ResponseStatus.SUBMITTED, "2026-03-05T12:00:00Z");

        mockMvc.perform(authorized(get(MY_APPLICATIONS), applicantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].eventTtl").value("동시각 나중 만든 신청"))
                .andExpect(jsonPath("$.data[1].eventTtl").value("동시각 먼저 만든 신청"))
                .andExpect(jsonPath("$.data[2].eventTtl").value("가장 오래된 신청"));
    }

    /* ── 성능 ─────────────────────────────────────────────── */

    /*
     * 신청이 몇 건이든 질의는 셋이다 — 응답 1 + 행사 1 + 참가자 1 (DB-13). 못 박아 두지 않으면
     * 매핑을 조금만 손대도 조용히 N+1로 되돌아간다.
     */
    @Test
    void queryCountIsFixedRegardlessOfApplicationCount() {
        for (int index = 1; index <= 3; index++) {
            FormEntity form = saveOpenForm();
            Long eventId = savePublishedEvent("EVENT", "집계 행사 " + index, form);
            saveResponse(
                    form, applicant, ResponseStatus.ACCEPTED, "2026-03-0" + index + "T12:00:00Z");
            saveParticipant(eventId, applicant, EventParticipantStatus.CONFIRMED);
        }
        entityManager.flush();
        entityManager.clear();

        Statistics statistics =
                entityManager
                        .getEntityManagerFactory()
                        .unwrap(SessionFactory.class)
                        .getStatistics();
        statistics.clear();

        List<MyApplicationResponse> applications =
                myApplicationService.getMyApplications(applicant);

        assertThat(applications).hasSize(3);
        assertThat(applications)
                .allSatisfy(
                        application ->
                                assertThat(application.applicationStatus().code())
                                        .isEqualTo("CONFIRMED"));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
    }

    /** 신청이 없으면 뒤 두 질의를 부르지 않는다 — 빈 in 절은 DB마다 다르게 처리된다 */
    @Test
    void queryCountIsOneWhenNoApplication() {
        entityManager.flush();
        entityManager.clear();

        Statistics statistics =
                entityManager
                        .getEntityManagerFactory()
                        .unwrap(SessionFactory.class)
                        .getStatistics();
        statistics.clear();

        assertThat(myApplicationService.getMyApplications(applicant)).isEmpty();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    /* ── 인증 계단 ─────────────────────────────────────────── */

    @Test
    void unauthenticatedRequestReturns401() throws Exception {
        mockMvc.perform(get(MY_APPLICATIONS).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    /** 가입하지 않은 주체는 401이 아니라 403 SIGNUP_REQUIRED다 — 프론트가 가입 화면으로 보낸다 */
    @Test
    void notSignedUpRequestReturns403SignupRequired() throws Exception {
        mockMvc.perform(authorized(get(MY_APPLICATIONS), UUID.randomUUID()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIGNUP_REQUIRED"));
    }

    /* ── 헬퍼 ─────────────────────────────────────────────── */

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
                FormEntity.create(applicant, "신청 폼", composition, null, null, FormStatus.OPEN));
    }

    private Long savePublishedEvent(String classificationCode, String title, FormEntity form) {
        EventEntity event = newEvent(classificationCode, title, form);
        event.changeStatus(EventStatusAction.PUBLISH);
        return eventRepository.saveAndFlush(event).getId();
    }

    private Long saveDraftEvent(String classificationCode, String title, FormEntity form) {
        return eventRepository.saveAndFlush(newEvent(classificationCode, title, form)).getId();
    }

    private EventEntity newEvent(String classificationCode, String title, FormEntity form) {
        EventClassificationEntity classification =
                eventClassificationRepository.findById(classificationCode).orElseThrow();
        return EventEntity.create(
                classification,
                applicant,
                title,
                "# 안내",
                null,
                form,
                Instant.parse("2026-04-01T01:00:00Z"),
                Instant.parse("2026-04-01T05:00:00Z"),
                "학생회관",
                null);
    }

    /** 게시된 행사를 보관으로 보낸다 — 전이표가 PUBLISHED → ARCHIVED만 허용한다 */
    private void archive(Long eventId) {
        EventEntity event = eventRepository.findById(eventId).orElseThrow();
        event.changeStatus(EventStatusAction.ARCHIVE);
        eventRepository.flush();
    }

    /*
     * 제출된 응답 하나. 심사 결과는 검토 API(#141)를 부르지 않고 전이만 태운다 — 여기서 확인할
     * 것은 파생 규칙이지 검토 규칙이 아니다.
     */
    private Long saveResponse(
            FormEntity form, MemberEntity respondent, ResponseStatus status, String submittedAt) {
        FormResponseHistoryEntity response =
                FormResponseHistoryEntity.createSubmitted(
                        form,
                        respondent,
                        ResponseContent.of(Map.of("q1", "홍길동")),
                        Instant.parse(submittedAt));
        if (status != ResponseStatus.SUBMITTED) {
            response.changeStatus(status);
        }
        return formResponseHistoryRepository.saveAndFlush(response).getId();
    }

    /*
     * 명단 한 줄. 등록 팩토리는 취소로 시작하는 등록을 받지 않으므로(그것이 규칙이다) 취소
     * 표본은 확정으로 만든 뒤 전이시킨다 — 테스트를 위해 규칙에 예외를 두지 않는다.
     */
    private Long saveParticipant(Long eventId, MemberEntity member, EventParticipantStatus status) {
        EventEntity event = eventRepository.findById(eventId).orElseThrow();
        boolean cancel = status == EventParticipantStatus.CANCELLED;
        EventParticipantEntity participant =
                EventParticipantEntity.register(
                        event,
                        member,
                        cancel ? EventParticipantStatus.CONFIRMED : status,
                        null,
                        applicant);
        eventParticipantRepository.saveAndFlush(participant);
        if (cancel) {
            participant.changeStatus(EventParticipantStatus.CANCELLED);
            eventParticipantRepository.flush();
        }
        return participant.getId();
    }

    /*
     * 인증 주체가 될 회원은 토큰(= JWT의 sub)과 같은 authUserId로 만들어야 한다. 스텁 디코더가
     * 토큰 문자열을 그대로 sub로 쓰므로, 다른 UUID로 만들면 그 회원은 인증 컨버터에게 보이지
     * 않아 403 SIGNUP_REQUIRED로 끊긴다.
     */
    private MemberEntity saveMember(UUID authUserId, String name) {
        String studentNumber = "2026%04d".formatted(studentNumberSeq++);
        return MemberFixture.save(
                memberRepository,
                memberGradeRepository,
                memberStatusRepository,
                authUserId,
                studentNumber,
                name,
                studentNumber + "@soongsil.ac.kr",
                MemberStatusCode.ENROLLED);
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
