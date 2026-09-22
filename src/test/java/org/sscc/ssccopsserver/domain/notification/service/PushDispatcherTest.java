package org.sscc.ssccopsserver.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.notification.code.NotificationApp;
import org.sscc.ssccopsserver.domain.notification.code.NotificationType;
import org.sscc.ssccopsserver.domain.notification.dto.PushPayload;
import org.sscc.ssccopsserver.domain.notification.entity.NotificationTypeRecipientEntity;
import org.sscc.ssccopsserver.domain.notification.entity.PushSubscriptionEntity;
import org.sscc.ssccopsserver.domain.notification.repository.NotificationTypeRecipientRepository;
import org.sscc.ssccopsserver.domain.notification.repository.PushSubscriptionRepository;

import com.fasterxml.jackson.databind.ObjectMapper;

/*
 * 발송이 기준표를 지나는지 (#535 · ADR-0047).
 *
 * 컨텍스트 없이 본다 — 확인하려는 것이 «어느 구독으로 보냈나» 하나라 DB도 스프링도 필요 없고,
 * 알림 행이 생기는 경로는 이미 세 리스너 테스트가 전용 H2에서 본다.
 *
 * 회원 엔티티를 mock 으로 두는 것은 `PushSubscriptionEntity.subscribe`가 회원을 요구하는데 이
 * 테스트가 회원에 대해 묻는 것이 «id가 같은가»뿐이기 때문이다(구독 묶음의 키).
 */
@ExtendWith(MockitoExtension.class)
class PushDispatcherTest {

    private static final Long ME = 7L;

    @Mock private PushSubscriptionRepository pushSubscriptionRepository;
    @Mock private WebPushSender webPushSender;
    @Mock private NotificationTypeRecipientRepository recipientRepository;
    @Mock private MemberEntity member;

    private PushDispatcher dispatcher;
    private NotificationRoutingPolicy routingPolicy;

    @BeforeEach
    void setUp() {
        routingPolicy = new NotificationRoutingPolicy(recipientRepository);
        dispatcher =
                new PushDispatcher(
                        pushSubscriptionRepository,
                        webPushSender,
                        new ObjectMapper(),
                        routingPolicy);
    }

    /*
     * 기준표가 «응답 승인 → WWW»라고 말하면, 어드민 브라우저만 등록해 둔 회원에게는 푸시가 가지
     * 않는다 — 알림 행은 이미 만들어졌고 목록에는 남는다(ADR-0045의 «푸시는 최선 노력»).
     */
    @Test
    void doesNotPushToAnAdminSubscriptionWhenTheTypeIsRoutedToWwwOnly() {
        givenRoutedTo(NotificationType.RESPONSE_ACCEPTED, NotificationApp.WWW);
        givenSubscriptions(subscription(NotificationApp.ADMIN, "admin-only"));

        int delivered =
                dispatcher.dispatch(
                        List.of(
                                notification(
                                        NotificationType.RESPONSE_ACCEPTED, NotificationApp.WWW)));

        assertThat(delivered).isZero();
        verify(webPushSender, never()).send(any(), anyString());
    }

    /* 유형 하나가 여러 앱을 가리키면 그 앱들의 구독 전부로 간다 */
    @Test
    void pushesToEveryAppTheTypeIsRoutedTo() {
        givenRoutedTo(
                NotificationType.APPROVAL_REQUESTED, NotificationApp.ADMIN, NotificationApp.LMS);
        givenSubscriptions(
                subscription(NotificationApp.ADMIN, "admin"),
                subscription(NotificationApp.LMS, "lms"),
                subscription(NotificationApp.WWW, "www"));
        when(webPushSender.send(any(), anyString())).thenReturn(WebPushOutcome.DELIVERED);

        int delivered =
                dispatcher.dispatch(
                        List.of(
                                notification(
                                        NotificationType.APPROVAL_REQUESTED,
                                        NotificationApp.ADMIN)));

        assertThat(delivered).isEqualTo(2);
        ArgumentCaptor<PushSubscriptionEntity> sent =
                ArgumentCaptor.forClass(PushSubscriptionEntity.class);
        verify(webPushSender, times(2)).send(sent.capture(), anyString());
        assertThat(sent.getAllValues())
                .extracting(PushSubscriptionEntity::getApp)
                .containsExactlyInAnyOrder(NotificationApp.ADMIN, NotificationApp.LMS);
    }

    /*
     * 기준표에 행이 없는 유형(지금 TEST)은 **그 알림 행의 app**을 따른다 — admin에서 누른 테스트
     * 알림은 admin에 등록된 기기로만 간다.
     */
    @Test
    void anUnregisteredTypeFollowsTheSendingAppOnTheRow() {
        when(recipientRepository.findAll()).thenReturn(List.of());
        givenSubscriptions(
                subscription(NotificationApp.ADMIN, "admin"),
                subscription(NotificationApp.WWW, "www"));
        when(webPushSender.send(any(), anyString())).thenReturn(WebPushOutcome.DELIVERED);

        int delivered =
                dispatcher.dispatch(
                        List.of(notification(NotificationType.TEST, NotificationApp.ADMIN)));

        assertThat(delivered).isEqualTo(1);
        ArgumentCaptor<PushSubscriptionEntity> sent =
                ArgumentCaptor.forClass(PushSubscriptionEntity.class);
        verify(webPushSender).send(sent.capture(), anyString());
        assertThat(sent.getValue().getApp()).isEqualTo(NotificationApp.ADMIN);
    }

    /* 죽은 구독을 지우는 종전 동작은 필터를 지난 구독에 대해 그대로다 */
    @Test
    void stillDeletesDeadSubscriptionsThatPassedTheFilter() {
        givenRoutedTo(NotificationType.RESPONSE_REJECTED, NotificationApp.WWW);
        PushSubscriptionEntity dead = subscription(NotificationApp.WWW, "dead");
        givenSubscriptions(dead);
        when(webPushSender.send(any(), anyString())).thenReturn(WebPushOutcome.GONE);

        int delivered =
                dispatcher.dispatch(
                        List.of(
                                notification(
                                        NotificationType.RESPONSE_REJECTED, NotificationApp.WWW)));

        assertThat(delivered).isZero();
        verify(pushSubscriptionRepository).deleteById(dead.getId());
    }

    /* ── helpers ──────────────────────────────────────────────── */

    private void givenRoutedTo(NotificationType type, NotificationApp... apps) {
        when(recipientRepository.findAll())
                .thenReturn(
                        Arrays.stream(apps)
                                .map(app -> NotificationTypeRecipientEntity.route(type, app))
                                .toList());
    }

    private void givenSubscriptions(PushSubscriptionEntity... subscriptions) {
        when(member.getId()).thenReturn(ME);
        when(pushSubscriptionRepository.findAllByMemberIds(any()))
                .thenReturn(List.of(subscriptions));
    }

    private PushSubscriptionEntity subscription(NotificationApp app, String endpoint) {
        return PushSubscriptionEntity.subscribe(
                member, app, "https://push.example.test/" + endpoint, "p256dh", "auth", null);
    }

    private CreatedNotification notification(NotificationType type, NotificationApp app) {
        return new CreatedNotification(
                ME, new PushPayload(1L, type, "제목", "내용", app, "/somewhere"));
    }
}
