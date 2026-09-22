package org.sscc.ssccopsserver.domain.notification.service;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.form.code.ResponseReviewAction;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;
import org.sscc.ssccopsserver.domain.form.event.FormResponseReviewedEvent;
import org.sscc.ssccopsserver.domain.form.repository.FormResponseHistoryRepository;
import org.sscc.ssccopsserver.domain.notification.code.NotificationApp;
import org.sscc.ssccopsserver.domain.notification.code.NotificationTargetType;
import org.sscc.ssccopsserver.domain.notification.code.NotificationType;
import org.sscc.ssccopsserver.domain.notification.entity.NotificationEntity;
import org.sscc.ssccopsserver.domain.notification.repository.NotificationRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/*
 * 폼 응답 검토 → 알림 행 (#528 · ssccops#453 · ADR-0045).
 *
 * 수신자는 **응답자 한 사람**이다 — 검토 결과는 낸 사람에게만 뜻이 있다. 검토자 본인의 응답이면
 * 생략한다(자기가 한 일을 자기에게 알리지 않는다 — `SubWorkNotificationService`와 같은 규칙).
 *
 * **REQUIRES_NEW다.** 부르는 쪽(`FormResponseReviewNotificationListener`)이 커밋 뒤 비동기 스레드라
 * 바깥 트랜잭션은 없지만, «검토와 다른 트랜잭션»이라는 사실을 선언으로 남긴다 — 누군가 동기
 * 리스너로 되돌려도 알림 실패가 검토를 롤백시키지 않는다.
 *
 * 응답을 **다시 읽는다**(이벤트에는 식별자만 실린다). 그사이 지워졌으면(회원 삭제 cascade) 알림도
 * 없다. `app = WWW`이고 링크는 «내 응답»의 허브다 — 기획안(`sys_form_cd = PROPOSAL`)은
 * `/me/proposals`, 그 밖은 `/me/responses`(#453 «기획안 링크도 www» — lms에는 기획안 상세가 없다).
 * 폼이 지워졌어도 응답 행은 남으므로 알림은 간다 — 검토 경로 자체가 지워진 폼을 404로 막으니
 * 실제로는 검토 뒤 폼이 지워진 경우뿐이고, 그때도 결과는 응답자의 것이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FormResponseNotificationService {

    private final FormResponseHistoryRepository formResponseHistoryRepository;
    private final NotificationRepository notificationRepository;

    /** 검토 하나에 대한 알림 행. 응답자가 검토자 본인이면 빈 목록 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<CreatedNotification> createForReview(FormResponseReviewedEvent event) {
        NotificationType type = typeOf(event.action());
        if (type == null) {
            return List.of(); // SUBMIT — 응답자가 한 일이라 알리는 사건이 아니다
        }

        Optional<FormResponseHistoryEntity> found =
                formResponseHistoryRepository.findById(event.formResponseId());
        if (found.isEmpty()) {
            log.info(
                    "form response {} not found for notification — skipped ({})",
                    event.formResponseId(),
                    event.action());
            return List.of();
        }
        FormResponseHistoryEntity response = found.get();
        if (response.getMember().getId().equals(event.reviewerId())) {
            return List.of();
        }

        NotificationEntity saved =
                notificationRepository.save(
                        NotificationEntity.create(
                                response.getMember(),
                                type,
                                FormResponseNotificationText.title(type, response),
                                FormResponseNotificationText.body(response),
                                NotificationApp.WWW,
                                FormResponseNotificationText.linkPath(response),
                                NotificationTargetType.FORM_RESPONSE,
                                response.getId(),
                                null));
        return List.of(CreatedNotification.of(saved));
    }

    private static NotificationType typeOf(ResponseReviewAction action) {
        return switch (action) {
            case ACCEPT -> NotificationType.RESPONSE_ACCEPTED;
            case REJECT -> NotificationType.RESPONSE_REJECTED;
            case REQUEST_CHANGES -> NotificationType.RESPONSE_CHANGES_REQUESTED;
            case SUBMIT -> null;
        };
    }
}
