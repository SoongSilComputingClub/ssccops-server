package org.sscc.ssccopsserver.domain.notification.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/*
 * 마감 알림·보존 정리의 시계 (ssccops#446 · ADR-0045).
 *
 * `@Scheduled`는 `global/config/SchedulingConfig`가 연 문(ADR-0041 · DatabaseKeepAlive가 첫 손님)을
 * 그대로 쓴다. 기본 스케줄러는 스레드 하나다 — 하루 한 번 09:00과 주 한 번 04:30이 겹칠 일이 없고,
 * 겹쳐도 몇 초 밀릴 뿐이다.
 *
 * **09:00 KST 하루 한 번**이지 마감 시각 기준이 아니다 — 마감은 날짜 단위 판정이고 밤에 울리는
 * 알림은 꺼진다(#446). 판정과 중복 방지는 `DeadlineNotificationService`가 한다(테스트는 그쪽을
 * 직접 부른다 — 스케줄러는 시각만 안다).
 *
 * 예외는 여기서 잡는다. `@Scheduled` 메서드가 던져도 스케줄러는 다음 회차를 돌리지만 로그가
 * `TaskUtils`의 일반 문구라 자리를 말하지 않는다.
 *
 * `enabled=false`면 빈이 서지 않는다 — test 프로필이 그렇다(공용 컨텍스트에서 09:00에 실제 알림이
 * 생기면 «알림이 0건»을 전제한 테스트가 시각에 따라 흔들린다 · DatabaseKeepAlive와 같은 판단).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "ssccops.notification.deadline.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class DeadlineNotificationScheduler {

    private final DeadlineNotificationService deadlineNotificationService;

    /** 매일 09:00 KST — D-1·첫 지연일 */
    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Seoul")
    public void notifyDeadlines() {
        try {
            deadlineNotificationService.notifyDeadlines();
        } catch (RuntimeException e) {
            log.error("deadline notification run failed — 다음 09:00에 다시 돈다", e);
        }
    }

    /** 매주 월요일 04:30 KST — 읽은 지 90일 지난 알림 정리(DatabaseKeepAlive의 04:00 뒤) */
    @Scheduled(cron = "0 30 4 * * MON", zone = "Asia/Seoul")
    public void purgeReadNotifications() {
        try {
            deadlineNotificationService.purgeReadNotifications();
        } catch (RuntimeException e) {
            log.error("read notification purge failed — 다음 주에 다시 돈다", e);
        }
    }
}
