package org.sscc.ssccopsserver.domain.notification.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.notification.entity.PushSubscriptionEntity;
import org.sscc.ssccopsserver.domain.notification.repository.PushSubscriptionRepository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/*
 * 만들어진 알림 행을 수신자의 구독 전부에 푸시로 보낸다 (ssccops#446 · ADR-0045).
 *
 * **트랜잭션 밖에서 돈다.** 알림 행은 이미 커밋됐고(`CreatedNotification`), 푸시 서비스 HTTP 왕복
 * (구독당 최대 10초)을 DB 커넥션을 쥔 채 하지 않는다. 구독 조회는 리포지토리 호출 자체의 짧은
 * 트랜잭션이다.
 *
 * 결과는 셋이다 — 갔다(아무 로그 없음) · 구독이 죽었다(행 삭제 · INFO) · 실패(ERROR, 발송기가
 * 남긴다). 발송 이력 표는 없다(ADR-0045). 한 구독의 실패가 다음 구독을 막지 않는다.
 *
 * 죽은 구독의 삭제는 `deleteById`다 — 같은 endpoint를 그사이 다른 회원이 다시 등록했어도 endpoint가
 * 같으면 행도 같으므로(uk_push_sbscrp_endpt) 새 구독을 지우는 일은 없다(푸시 서비스가 404를 준
 * endpoint는 누가 등록했든 죽은 것이다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PushDispatcher {

    private final PushSubscriptionRepository pushSubscriptionRepository;
    private final WebPushSender webPushSender;
    private final ObjectMapper objectMapper;

    /** 보낸다. 돌려주는 값은 푸시 서비스가 받아 준(DELIVERED) 구독 수 — 테스트 알림의 «n대»다(#528) */
    public int dispatch(List<CreatedNotification> notifications) {
        if (notifications.isEmpty()) {
            return 0;
        }
        Set<Long> recipientIds =
                notifications.stream()
                        .map(CreatedNotification::recipientId)
                        .collect(Collectors.toSet());
        Map<Long, List<PushSubscriptionEntity>> subscriptionsByMember =
                pushSubscriptionRepository.findAllByMemberIds(recipientIds).stream()
                        .collect(Collectors.groupingBy(s -> s.getMember().getId()));
        if (subscriptionsByMember.isEmpty()) {
            return 0;
        }

        int delivered = 0;
        for (CreatedNotification notification : notifications) {
            List<PushSubscriptionEntity> subscriptions =
                    subscriptionsByMember.getOrDefault(notification.recipientId(), List.of());
            if (subscriptions.isEmpty()) {
                continue;
            }
            String payload = toJson(notification);
            if (payload == null) {
                continue;
            }
            for (PushSubscriptionEntity subscription : subscriptions) {
                WebPushOutcome outcome = webPushSender.send(subscription, payload);
                if (outcome == WebPushOutcome.DELIVERED) {
                    delivered++;
                } else if (outcome == WebPushOutcome.GONE) {
                    pushSubscriptionRepository.deleteById(subscription.getId());
                }
            }
        }
        return delivered;
    }

    private String toJson(CreatedNotification notification) {
        try {
            return objectMapper.writeValueAsString(notification.payload());
        } catch (JsonProcessingException e) {
            // 페이로드는 문자열·enum·숫자뿐이라 여기 올 수 없다 — 온다면 코드 결함이다
            log.error(
                    "push payload for notification {} could not be serialized",
                    notification.payload().notificationId(),
                    e);
            return null;
        }
    }
}
