package org.sscc.ssccopsserver.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
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
import org.sscc.ssccopsserver.domain.event.code.EventStatusAction;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.event.repository.EventClassificationRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventRepository;
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

import com.jayway.jsonpath.JsonPath;

/*
 * 행사 참가 상태 변경 → 알림 행 → 푸시 (#528 · ssccops#453). 수용 기준 2를 실제 명단 API 경로로 본다.
 *
 * **@Transactional을 걸지 않는다** — 리스너가 AFTER_COMMIT · @Async라 전용 H2에서 실제로 커밋하고
 * Awaitility로 기다린다(SubWorkNotificationListenerTest와 같은 이유). 픽스처는 클래스당 한 번.
 *
 * 세 경로를 본다 — 등록(처음 명단에 오름 · previous 없음) · 전이(대기 → 확정) · 본인이 바꾼 것
 * (운영자가 자기를 등록·전이 → 알림 없음).
 */
@SpringBootTest(
        properties =
                "spring.datasource.url="
                    + "jdbc:h2:mem:event-participant-notification;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventParticipantStatusNotificationListenerTest {

    private static final UUID MANAGER = UUID.randomUUID();
    private static final Duration WAIT = Duration.ofSeconds(10);

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private MemberRoleRepository memberRoleRepository;
    @Autowired private MemberRoleClassificationRepository memberRoleClassificationRepository;
    @Autowired private MemberRoleAssignmentRepository memberRoleAssignmentRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private EventClassificationRepository eventClassificationRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private PushSubscriptionRepository pushSubscriptionRepository;

    @MockitoBean private WebPushSender webPushSender;

    private MemberEntity manager;
    private MemberEntity applicant;
    private int studentNumberSeq = 1;

    @BeforeAll
    void fixtures() {
        manager = save(MANAGER, "행사운영자");
        MemberRoleFixture.assign(
                memberRoleRepository,
                memberRoleClassificationRepository,
                memberRoleAssignmentRepository,
                manager,
                "최고관리자");
        applicant = save(UUID.randomUUID(), "신청자");
        pushSubscriptionRepository.save(
                PushSubscriptionEntity.subscribe(
                        applicant,
                        NotificationApp.WWW,
                        "https://push.example.test/applicant",
                        "p256dh",
                        "auth",
                        null));
    }

    /* 등록으로 처음 명단에 오르는 것도 알린다 — 확정으로 등록하면 [참가 확정] */
    @Test
    void registeringAsConfirmedNotifiesTheParticipant() throws Exception {
        when(webPushSender.send(any(), anyString())).thenReturn(WebPushOutcome.DELIVERED);
        Long eventId = saveEvent("2026 봄 MT");

        Long participantId = register(eventId, applicant.getId(), "CONFIRMED");

        await().atMost(WAIT)
                .untilAsserted(
                        () ->
                                assertThat(
                                                notificationsOf(
                                                        applicant.getId(),
                                                        NotificationType.APPLICATION_CONFIRMED,
                                                        participantId))
                                        .hasSize(1));
        NotificationEntity notification =
                notificationsOf(
                                applicant.getId(),
                                NotificationType.APPLICATION_CONFIRMED,
                                participantId)
                        .get(0);
        assertThat(notification.getTitle()).isEqualTo("[참가 확정] 2026 봄 MT");
        assertThat(notification.getBody())
                .matches("2026 봄 MT · 변경 \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}");
        assertThat(notification.getApp()).isEqualTo(NotificationApp.WWW);
        assertThat(notification.getLinkPath()).isEqualTo("/me/applications");
        assertThat(notification.getTargetType())
                .isEqualTo(NotificationTargetType.EVENT_PARTICIPANT);
        assertThat(notification.getNotificationKey()).isNull();

        verify(webPushSender, timeout(WAIT.toMillis()))
                .send(
                        argThat(subscription -> subscription.isOwnedBy(applicant.getId())),
                        argThat(
                                json ->
                                        json.contains("\"type\":\"APPLICATION_CONFIRMED\"")
                                                && json.contains("\"app\":\"WWW\"")
                                                && json.contains(
                                                        "\"linkPath\":\"/me/applications\"")));
    }

    /* 대기로 등록 → [대기], 승격 → [참가 확정], 취소 → [취소] — 전이마다 한 행 */
    @Test
    void eachTransitionNotifiesWithItsOwnType() throws Exception {
        when(webPushSender.send(any(), anyString())).thenReturn(WebPushOutcome.DELIVERED);
        Long eventId = saveEvent("해커톤");
        Long participantId = register(eventId, applicant.getId(), "WAITLISTED");
        awaitOne(NotificationType.APPLICATION_WAITLISTED, participantId);
        assertThat(
                        notificationsOf(
                                        applicant.getId(),
                                        NotificationType.APPLICATION_WAITLISTED,
                                        participantId)
                                .get(0)
                                .getTitle())
                .isEqualTo("[대기] 해커톤");

        changeStatus(eventId, participantId, "CONFIRMED");
        awaitOne(NotificationType.APPLICATION_CONFIRMED, participantId);

        changeStatus(eventId, participantId, "CANCELLED");
        awaitOne(NotificationType.APPLICATION_CANCELLED, participantId);
        assertThat(
                        notificationsOf(
                                        applicant.getId(),
                                        NotificationType.APPLICATION_CANCELLED,
                                        participantId)
                                .get(0)
                                .getTitle())
                .isEqualTo("[취소] 해커톤");
    }

    /* 운영자가 자기 자신을 등록·전이하면 자기에게는 가지 않는다 (#453 «회원 본인이 바꾼 것이면 생략») */
    @Test
    void changingOwnParticipationDoesNotNotify() throws Exception {
        when(webPushSender.send(any(), anyString())).thenReturn(WebPushOutcome.DELIVERED);
        Long eventId = saveEvent("운영진 워크숍");
        Long selfId = register(eventId, manager.getId(), "WAITLISTED");
        changeStatus(eventId, selfId, "CONFIRMED");
        // 남의 등록 알림이 온 것이 «리스너가 돌았다»의 증거이고, 그 뒤에도 본인 알림은 없어야 한다
        Long otherId = register(eventId, applicant.getId(), "CONFIRMED");
        awaitOne(NotificationType.APPLICATION_CONFIRMED, otherId);

        Thread.sleep(500);
        assertThat(
                        notificationRepository.findAll().stream()
                                .filter(n -> n.getMember().getId().equals(manager.getId()))
                                .toList())
                .isEmpty();
    }

    /* ── helpers ──────────────────────────────────────────────── */

    private void awaitOne(NotificationType type, Long participantId) {
        await().atMost(WAIT)
                .untilAsserted(
                        () ->
                                assertThat(notificationsOf(applicant.getId(), type, participantId))
                                        .hasSize(1));
    }

    private List<NotificationEntity> notificationsOf(
            Long memberId, NotificationType type, Long participantId) {
        return notificationRepository.findAll().stream()
                .filter(n -> n.getMember().getId().equals(memberId))
                .filter(n -> n.getType() == type)
                .filter(n -> n.getTargetId().equals(participantId))
                .toList();
    }

    private Long register(Long eventId, Long memberId, String status) throws Exception {
        String response =
                mockMvc.perform(
                                post("/v1/events/{eventId}/participants", eventId)
                                        .header("Authorization", "Bearer " + MANAGER)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"mbrId\": %d, \"ptcpSttsCd\": \"%s\"}"
                                                        .formatted(memberId, status)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.parse(response).read("$.data.participant.eventPtcpId", Long.class);
    }

    private void changeStatus(Long eventId, Long participantId, String status) throws Exception {
        mockMvc.perform(
                        patch(
                                        "/v1/events/{eventId}/participants/{participantId}",
                                        eventId,
                                        participantId)
                                .header("Authorization", "Bearer " + MANAGER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"ptcpSttsCd\": \"%s\"}".formatted(status)))
                .andExpect(status().isOk());
    }

    private Long saveEvent(String title) {
        EventEntity event =
                EventEntity.create(
                        eventClassificationRepository.findById("RECRUIT").orElseThrow(),
                        manager,
                        title,
                        "# 안내",
                        null,
                        null,
                        null,
                        null,
                        "학생회관",
                        null);
        event.changeStatus(EventStatusAction.PUBLISH);
        return eventRepository.saveAndFlush(event).getId();
    }

    private MemberEntity save(UUID authUserId, String name) {
        String studentNumber = "2026%04d".formatted(studentNumberSeq++);
        return MemberFixture.save(
                memberRepository,
                memberGradeRepository,
                memberStatusRepository,
                authUserId,
                studentNumber,
                name,
                studentNumber + "@soongsil.ac.kr");
    }
}
