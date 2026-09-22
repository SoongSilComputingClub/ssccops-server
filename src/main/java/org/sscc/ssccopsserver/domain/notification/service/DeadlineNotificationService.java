package org.sscc.ssccopsserver.domain.notification.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.notification.code.NotificationApp;
import org.sscc.ssccopsserver.domain.notification.code.NotificationTargetType;
import org.sscc.ssccopsserver.domain.notification.code.NotificationType;
import org.sscc.ssccopsserver.domain.notification.entity.NotificationEntity;
import org.sscc.ssccopsserver.domain.notification.repository.NotificationRepository;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.WorkStatus;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/*
 * 마감 알림 — D-1과 첫 지연일 (ssccops#446 · ADR-0045).
 *
 * 스케줄러(`DeadlineNotificationScheduler`)가 매일 09:00 KST에 `notifyDeadlines()`를 부른다.
 * 마감(`ddln_dt`)이 **내일**인 미완료 하위 업무의 담당자에게 `DEADLINE_DUE`, **어제**인 미완료 건에
 * `DEADLINE_OVERDUE`. 마감 판정은 시각이 아니라 **일자**다(`DeadlinePolicy` · #121) — 그래서 «내일»은
 * 서비스 표준 시간대의 내일 0시부터 모레 0시 직전까지이고, 하루 한 번 09:00인 것은 밤에 울리는
 * 알림을 끄기 위해서다(#446).
 *
 * **한 번만 간다.** `noti_key = "{유형}:SUB_WORK:{id}:{마감일}"`이고 (mbr_id, noti_key)가 UNIQUE라
 * 재기동·중복 실행에도 두 번째 INSERT는 거절된다. 먼저 `exists`로 보고 건너뛰되(대부분의 중복은
 * 여기서 걸린다), 그 사이에 끼어든 경쟁은 UNIQUE 위반으로 잡는다 — **행마다 별도 트랜잭션**
 * (리포지토리 `save` 자체의 것 — `createOnce` 주석)이라 한 건의 위반이 그날 배치 전체를 롤백시키지
 * 않는다.
 *
 * 담당자가 바뀌면 새 담당자에게 다시 간다(키에 회원이 아니라 마감일이 들어 있고 UNIQUE가 회원별이다)
 * — «담당자가 마감을 모른다»를 막는 것이 목적이므로 맞다. 마감일이 바뀌면 새 마감일로 다시 간다.
 *
 * 완료(`DONE`)·지워진 건(`oper.del_dt`)은 제외 — 조회 자체가 그 둘을 거른다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeadlineNotificationService {

    /** 읽은 알림을 얼마 뒤에 지우는가 (ADR-0045 «읽은 알림은 90일 뒤 삭제 — 안 읽은 것은 남긴다») */
    static final Duration READ_RETENTION = Duration.ofDays(90);

    private final SubWorkRepository subWorkRepository;
    private final NotificationRepository notificationRepository;
    private final PushDispatcher pushDispatcher;
    private final Clock clock;

    /** 오늘 기준 D-1·첫 지연일 알림을 만들고 푸시를 보낸다. 만든 행 수를 돌려준다 */
    public int notifyDeadlines() {
        LocalDate today = LocalDate.now(clock);
        List<CreatedNotification> created = new ArrayList<>();
        created.addAll(notifyDueOn(today.plusDays(1), NotificationType.DEADLINE_DUE));
        created.addAll(notifyDueOn(today.minusDays(1), NotificationType.DEADLINE_OVERDUE));
        pushDispatcher.dispatch(created);
        if (!created.isEmpty()) {
            log.info("deadline notifications created: {} ({})", created.size(), today);
        }
        return created.size();
    }

    /** 읽은 지 90일이 지난 알림을 지운다. 지운 행 수를 돌려준다 */
    @Transactional
    public int purgeReadNotifications() {
        int deleted =
                notificationRepository.deleteReadBefore(clock.instant().minus(READ_RETENTION));
        if (deleted > 0) {
            log.info("read notifications purged: {}", deleted);
        }
        return deleted;
    }

    private List<CreatedNotification> notifyDueOn(LocalDate dueDate, NotificationType type) {
        Instant from = dueDate.atStartOfDay(clock.getZone()).toInstant();
        // 조회가 `<= to`(양끝 포함)라 다음 날 0시에서 1ms를 뺀다 — 정확히 0시 마감은 다음 날의 건이다
        Instant to = dueDate.plusDays(1).atStartOfDay(clock.getZone()).toInstant().minusMillis(1);

        List<CreatedNotification> created = new ArrayList<>();
        for (SubWorkEntity subWork :
                subWorkRepository.findAllDueBetweenExcludingStatus(from, to, WorkStatus.DONE)) {
            createOnce(subWork, type, dueDate).ifPresent(created::add);
        }
        return created;
    }

    /*
     * **트랜잭션 밖이다** — 클래스에도 이 메서드에도 @Transactional이 없다. `save`는 리포지토리
     * (SimpleJpaRepository)의 자체 트랜잭션이라 UNIQUE 위반이 그 호출에서 바로 던져지고, 바깥
     * 트랜잭션이 없으므로 rollback-only 표시가 다음 행으로 번지지 않는다. 배치 전체를 한 트랜잭션으로
     * 묶고 행마다 REQUIRES_NEW를 거는 안은 자기 호출이 프록시를 지나지 않아 성립하지 않고
     * (`ProposalFormSeeder`가 겪은 것), TransactionTemplate을 들이는 것보다 이쪽이 짧다.
     */
    private Optional<CreatedNotification> createOnce(
            SubWorkEntity subWork, NotificationType type, LocalDate dueDate) {
        MemberEntity recipient = subWork.getOperation().getPersonInCharge();
        String key = type.name() + ":SUB_WORK:" + subWork.getId() + ":" + dueDate;
        if (notificationRepository.existsByMemberIdAndNotificationKey(recipient.getId(), key)) {
            return Optional.empty();
        }
        try {
            NotificationEntity saved =
                    notificationRepository.save(
                            NotificationEntity.create(
                                    recipient,
                                    type,
                                    SubWorkNotificationText.title(type, subWork),
                                    SubWorkNotificationText.body(subWork),
                                    NotificationApp.ADMIN,
                                    SubWorkNotificationText.linkPath(subWork),
                                    NotificationTargetType.SUB_WORK,
                                    subWork.getId(),
                                    key));
            return Optional.of(CreatedNotification.of(saved));
        } catch (DataIntegrityViolationException e) {
            // exists와 save 사이에 다른 인스턴스가 먼저 넣었다 — 같은 사건이니 조용히 건너뛴다
            log.info("deadline notification already exists — skipped ({})", key);
            return Optional.empty();
        }
    }
}
