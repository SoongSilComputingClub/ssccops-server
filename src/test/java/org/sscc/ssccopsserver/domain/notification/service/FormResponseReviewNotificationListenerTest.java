package org.sscc.ssccopsserver.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
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
import org.sscc.ssccopsserver.domain.form.service.ProposalFormSeed;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleAssignmentRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleClassificationRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRoleRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.domain.notification.code.NotificationApp;
import org.sscc.ssccopsserver.domain.notification.code.NotificationTargetType;
import org.sscc.ssccopsserver.domain.notification.code.NotificationType;
import org.sscc.ssccopsserver.domain.notification.entity.NotificationEntity;
import org.sscc.ssccopsserver.domain.notification.entity.PushSubscriptionEntity;
import org.sscc.ssccopsserver.domain.notification.repository.NotificationRepository;
import org.sscc.ssccopsserver.domain.notification.repository.PushSubscriptionRepository;
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.MemberRoleFixture;
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

/*
 * 폼 응답 검토 → 알림 행 → 푸시 (#528 · ssccops#453). 수용 기준 1을 실제 검토 요청 경로로 본다.
 *
 * **@Transactional을 걸지 않는다.** 리스너가 AFTER_COMMIT이라 테스트 트랜잭션(커밋 없음)에서는
 * 돌지 않는다 — 전용 H2에서 실제로 커밋하고(SubWorkNotificationListenerTest와 같은 이유) @Async라
 * Awaitility로 기다린다. 픽스처는 DB에 남으므로 클래스당 한 번 세운다.
 *
 * 폼·응답은 리포지토리로 직접 만든다 — 확인하려는 것은 검토 → 알림이지 응답을 내는 API가 아니다
 * (EventParticipationControllerTest와 같은 판단). 기획안 링크 분기는 폼을 PROPOSAL로 지정해 본다 —
 * test 프로필은 회원 생성 시드가 꺼져 있고 부팅 시드는 회원이 없어 건너뛰므로 그 코드가 비어 있다.
 * 승인(ACCEPT)은 학술 이관 훅을 부르므로 기획안 쪽은 수정 요청으로 본다.
 *
 * 테스트 알림의 «pushed»(발송기가 받아 준 구독 수)도 여기서 본다 — 발송기가 @MockitoBean이고
 * 응답자의 구독이 있는 유일한 자리다. Noop·429는 NotificationControllerTest.
 */
@SpringBootTest(
        properties =
                "spring.datasource.url="
                    + "jdbc:h2:mem:form-response-notification;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FormResponseReviewNotificationListenerTest {

    private static final UUID REVIEWER = UUID.randomUUID();
    private static final UUID RESPONDENT = UUID.randomUUID();
    private static final Duration WAIT = Duration.ofSeconds(10);

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private FormRepository formRepository;
    @Autowired private FormResponseHistoryRepository formResponseHistoryRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private PushSubscriptionRepository pushSubscriptionRepository;

    @MockitoBean private WebPushSender webPushSender;

    private MemberEntity reviewer;
    private MemberEntity respondent;

    @BeforeAll
    void fixtures() {
        reviewer = save(REVIEWER, "20200001", "이서연", "reviewer@sscc.org");
        respondent = save(RESPONDENT, "20200002", "박준호", "respondent@sscc.org");
        MemberRoleFixture.assign(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                reviewer,
                "최고관리자");
        // 응답자의 브라우저 하나 — 발송기가 이 구독으로 불려야 한다
        pushSubscriptionRepository.save(
                PushSubscriptionEntity.subscribe(
                        respondent,
                        NotificationApp.WWW,
                        "https://push.example.test/respondent",
                        "p256dh",
                        "auth",
                        null));
    }

    /* 승인 → 응답자. 문구·링크·앱·대상이 #453 계약 그대로이고 응답자의 구독으로 푸시가 나간다 */
    @Test
    void acceptNotifiesTheRespondentWithResponsesLink() throws Exception {
        when(webPushSender.send(any(), anyString())).thenReturn(WebPushOutcome.DELIVERED);
        FormEntity form = saveForm("2026 MT 신청", false);
        Long responseId = saveResponse(form, respondent);

        review(form.getId(), responseId, "ACCEPTED", null).andExpect(status().isOk());

        await().atMost(WAIT)
                .untilAsserted(
                        () ->
                                assertThat(
                                                notificationsOf(
                                                        respondent.getId(),
                                                        NotificationType.RESPONSE_ACCEPTED,
                                                        responseId))
                                        .hasSize(1));
        NotificationEntity notification =
                notificationsOf(respondent.getId(), NotificationType.RESPONSE_ACCEPTED, responseId)
                        .get(0);
        assertThat(notification.getTitle()).isEqualTo("[승인] 2026 MT 신청");
        assertThat(notification.getBody())
                .startsWith("2026 MT 신청 · 검토 ")
                .matches("2026 MT 신청 · 검토 \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}");
        assertThat(notification.getApp()).isEqualTo(NotificationApp.WWW);
        assertThat(notification.getLinkPath()).isEqualTo("/me/responses");
        assertThat(notification.getTargetType()).isEqualTo(NotificationTargetType.FORM_RESPONSE);
        assertThat(notification.getNotificationKey()).isNull();

        verify(webPushSender, timeout(WAIT.toMillis()))
                .send(
                        argThat(subscription -> subscription.isOwnedBy(respondent.getId())),
                        argThat(
                                json ->
                                        json.contains("\"notificationId\":" + notification.getId())
                                                && json.contains("\"type\":\"RESPONSE_ACCEPTED\"")
                                                && json.contains("\"app\":\"WWW\"")
                                                && json.contains(
                                                        "\"linkPath\":\"/me/responses\"")));
    }

    /* 반려 → 응답자. 검토 의견은 알림에 실리지 않는다 */
    @Test
    void rejectNotifiesWithoutTheOpinion() throws Exception {
        when(webPushSender.send(any(), anyString())).thenReturn(WebPushOutcome.DELIVERED);
        FormEntity form = saveForm("동아리방 이용 신청", false);
        Long responseId = saveResponse(form, respondent);

        review(form.getId(), responseId, "REJECTED", "정원이 찼습니다").andExpect(status().isOk());

        await().atMost(WAIT)
                .untilAsserted(
                        () ->
                                assertThat(
                                                notificationsOf(
                                                        respondent.getId(),
                                                        NotificationType.RESPONSE_REJECTED,
                                                        responseId))
                                        .hasSize(1));
        NotificationEntity notification =
                notificationsOf(respondent.getId(), NotificationType.RESPONSE_REJECTED, responseId)
                        .get(0);
        assertThat(notification.getTitle()).isEqualTo("[반려] 동아리방 이용 신청");
        assertThat(notification.getBody()).doesNotContain("정원이 찼습니다");
    }

    /* 기획안(PROPOSAL) 응답의 수정 요청 → «내 기획안» 링크 */
    @Test
    void changesRequestedOnProposalLinksToMyProposals() throws Exception {
        when(webPushSender.send(any(), anyString())).thenReturn(WebPushOutcome.DELIVERED);
        FormEntity form = saveForm("스터디·프로젝트·트랙 기획안", true);
        Long responseId = saveResponse(form, respondent);

        review(form.getId(), responseId, "CHANGES_REQUESTED", "커리큘럼을 보완해 주세요")
                .andExpect(status().isOk());

        await().atMost(WAIT)
                .untilAsserted(
                        () ->
                                assertThat(
                                                notificationsOf(
                                                        respondent.getId(),
                                                        NotificationType.RESPONSE_CHANGES_REQUESTED,
                                                        responseId))
                                        .hasSize(1));
        NotificationEntity notification =
                notificationsOf(
                                respondent.getId(),
                                NotificationType.RESPONSE_CHANGES_REQUESTED,
                                responseId)
                        .get(0);
        assertThat(notification.getTitle()).isEqualTo("[수정 요청] 스터디·프로젝트·트랙 기획안");
        assertThat(notification.getLinkPath()).isEqualTo("/me/proposals");
        assertThat(notification.getApp()).isEqualTo(NotificationApp.WWW);
    }

    /* 검토자 본인의 응답이면 알림이 없다 (#453 «검토자 본인 응답이면 생략») */
    @Test
    void reviewingOwnResponseDoesNotNotify() throws Exception {
        when(webPushSender.send(any(), anyString())).thenReturn(WebPushOutcome.DELIVERED);
        FormEntity form = saveForm("운영진 설문", false);
        Long ownResponseId = saveResponse(form, reviewer);
        Long otherResponseId = saveResponse(form, respondent);

        review(form.getId(), ownResponseId, "ACCEPTED", null).andExpect(status().isOk());
        review(form.getId(), otherResponseId, "ACCEPTED", null).andExpect(status().isOk());

        // 남의 응답 알림이 온 것이 «리스너가 돌았다»의 증거이고, 그 뒤에도 본인 알림은 없어야 한다
        await().atMost(WAIT)
                .untilAsserted(
                        () ->
                                assertThat(
                                                notificationsOf(
                                                        respondent.getId(),
                                                        NotificationType.RESPONSE_ACCEPTED,
                                                        otherResponseId))
                                        .hasSize(1));
        Thread.sleep(500);
        assertThat(
                        notificationsOf(
                                reviewer.getId(),
                                NotificationType.RESPONSE_ACCEPTED,
                                ownResponseId))
                .isEmpty();
    }

    /* 테스트 알림 — 자기 구독 전부로 동기 푸시하고 받아 준 수를 돌려준다 (#528 · ssccops#454) */
    @Test
    void testNotificationPushesToMySubscriptionsAndReportsTheCount() throws Exception {
        when(webPushSender.send(any(), anyString())).thenReturn(WebPushOutcome.DELIVERED);

        mockMvc.perform(
                        post("/v1/notifications/test")
                                .header("Authorization", "Bearer " + RESPONDENT)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"app\": \"WWW\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notificationId").isNumber())
                .andExpect(jsonPath("$.data.pushed").value(1));

        verify(webPushSender)
                .send(
                        argThat(subscription -> subscription.isOwnedBy(respondent.getId())),
                        argThat(
                                json ->
                                        json.contains("\"type\":\"TEST\"")
                                                && json.contains("\"title\":\"[테스트] 알림이 잘 옵니다\"")
                                                && json.contains("\"linkPath\":\"/me\"")));
    }

    /* ── helpers ──────────────────────────────────────────────── */

    private List<NotificationEntity> notificationsOf(
            Long memberId, NotificationType type, Long responseId) {
        return notificationRepository.findAll().stream()
                .filter(n -> n.getMember().getId().equals(memberId))
                .filter(n -> n.getType() == type)
                .filter(n -> n.getTargetId().equals(responseId))
                .toList();
    }

    private ResultActions review(Long formId, Long responseId, String status, String opinion)
            throws Exception {
        String body =
                opinion == null
                        ? "{\"rspnsSttsCd\": \"%s\"}".formatted(status)
                        : "{\"rspnsSttsCd\": \"%s\", \"rvwOpnnCn\": \"%s\"}"
                                .formatted(status, opinion);
        return mockMvc.perform(
                post("/v1/forms/{formId}/responses/{responseId}/reviews", formId, responseId)
                        .header("Authorization", "Bearer " + REVIEWER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private FormEntity saveForm(String title, boolean proposal) {
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
        FormEntity form =
                FormEntity.create(reviewer, title, composition, null, null, FormStatus.OPEN);
        if (proposal) {
            form.designateAsSystemForm(ProposalFormSeed.SYSTEM_FORM_CODE);
        }
        return formRepository.saveAndFlush(form);
    }

    private Long saveResponse(FormEntity form, MemberEntity member) {
        return formResponseHistoryRepository
                .saveAndFlush(
                        FormResponseHistoryEntity.createSubmitted(
                                form,
                                member,
                                ResponseContent.of(Map.of("q1", "홍길동")),
                                Instant.parse("2026-03-10T12:00:00Z")))
                .getId();
    }

    private MemberEntity save(UUID authUserId, String studentNumber, String name, String email) {
        return MemberFixture.save(
                memberRepository,
                memberGradeRepository,
                memberStatusRepository,
                authUserId,
                studentNumber,
                name,
                email);
    }
}
