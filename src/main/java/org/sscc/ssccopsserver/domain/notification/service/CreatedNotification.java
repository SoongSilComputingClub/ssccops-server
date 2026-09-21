package org.sscc.ssccopsserver.domain.notification.service;

import org.sscc.ssccopsserver.domain.notification.dto.PushPayload;
import org.sscc.ssccopsserver.domain.notification.entity.NotificationEntity;

/*
 * 방금 커밋된 알림 행 하나 — 발송기에 넘기는 값 (ssccops#446).
 *
 * 엔티티가 아니라 이 record인 것은 만드는 트랜잭션(REQUIRES_NEW)과 보내는 자리(트랜잭션 밖)가
 * 다르기 때문이다. 트랜잭션이 닫힌 뒤의 엔티티는 준영속이라 `getMember()`를 건드리면
 * `LazyInitializationException`이고, 발송에 필요한 것은 «누구에게·무엇을» 두 값뿐이다.
 */
public record CreatedNotification(Long recipientId, PushPayload payload) {

    static CreatedNotification of(NotificationEntity notification) {
        return new CreatedNotification(
                notification.getMember().getId(), PushPayload.of(notification));
    }
}
