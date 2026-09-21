package org.sscc.ssccopsserver.domain.notification.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.service.AuthorityPolicy;
import org.sscc.ssccopsserver.domain.notification.code.NotificationApp;
import org.sscc.ssccopsserver.domain.notification.code.NotificationTargetType;
import org.sscc.ssccopsserver.domain.notification.code.NotificationType;
import org.sscc.ssccopsserver.domain.notification.entity.NotificationEntity;
import org.sscc.ssccopsserver.domain.notification.repository.NotificationRepository;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkTypeEntity;
import org.sscc.ssccopsserver.domain.operation.entity.TransitionAction;
import org.sscc.ssccopsserver.domain.operation.event.SubWorkTransitionedEvent;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/*
 * 하위 업무 전이 → 알림 행 (ssccops#446 · ADR-0045).
 *
 * 수신자는 **기존 정책으로만** 계산한다 — 승인 요청은 `AuthorityPolicy.memberIdsWithAuthority(그
 * 유형의 결재 권한)`, 승인·반려는 `oper.personInCharge`. 여기서 새 규칙을 만들면 «승인 버튼은 안
 * 보이는데 알림은 오는» 사람이 생긴다(#446 «판단과 기각한 대안»). 수행자 본인은 뺀다 — 자기가 한
 * 일을 자기에게 알리지 않는다(담당자가 자기 건을 승인하는 경우 포함).
 *
 * **REQUIRES_NEW다.** 부르는 쪽(`SubWorkTransitionNotificationListener`)이 커밋 뒤 비동기 스레드라
 * 바깥 트랜잭션은 없지만, 이 메서드가 «전이와 다른 트랜잭션»이라는 사실을 선언으로 남긴다 —
 * 누군가 동기 리스너로 되돌려도 알림 실패가 전이를 롤백시키지 않는다.
 *
 * 하위 업무를 **다시 읽는다**(이벤트에는 식별자만 실린다). 그사이 지워졌거나 없으면 알림도 없다 —
 * 지워진 건의 알림을 눌러 404를 보는 것보다 낫다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubWorkNotificationService {

    private final SubWorkRepository subWorkRepository;
    private final MemberRepository memberRepository;
    private final NotificationRepository notificationRepository;
    private final AuthorityPolicy authorityPolicy;

    /** 전이 하나에 대한 알림 행들을 만든다. 수신자가 없으면 빈 목록 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<CreatedNotification> createForTransition(SubWorkTransitionedEvent event) {
        Optional<SubWorkEntity> found =
                subWorkRepository.findByIdAndOperationDeletedAtIsNull(event.subWorkId());
        if (found.isEmpty()) {
            log.info(
                    "sub-work {} not found for notification — skipped ({})",
                    event.subWorkId(),
                    event.action());
            return List.of();
        }
        SubWorkEntity subWork = found.get();

        NotificationType type = typeOf(event.action());
        if (type == null) {
            return List.of(); // START — 알리는 사건이 아니다
        }

        Set<Long> recipientIds = recipientsOf(type, subWork);
        recipientIds.remove(event.performerId());
        if (recipientIds.isEmpty()) {
            return List.of();
        }

        String title = SubWorkNotificationText.title(type, subWork);
        String body = SubWorkNotificationText.body(subWork);
        String linkPath = SubWorkNotificationText.linkPath(subWork);

        List<CreatedNotification> created = new ArrayList<>();
        for (MemberEntity recipient : memberRepository.findAllById(recipientIds)) {
            NotificationEntity saved =
                    notificationRepository.save(
                            NotificationEntity.create(
                                    recipient,
                                    type,
                                    title,
                                    body,
                                    NotificationApp.ADMIN,
                                    linkPath,
                                    NotificationTargetType.SUB_WORK,
                                    subWork.getId(),
                                    null));
            created.add(CreatedNotification.of(saved));
        }
        return created;
    }

    private static NotificationType typeOf(TransitionAction action) {
        return switch (action) {
            case REQUEST_REVIEW -> NotificationType.APPROVAL_REQUESTED;
            case APPROVE_COMPLETE -> NotificationType.APPROVAL_APPROVED;
            case REJECT -> NotificationType.APPROVAL_REJECTED;
            case START -> null;
        };
    }

    /*
     * 승인 요청 → 결재 권한 보유자 전원. 승인이 필요 없는 유형은 검토 요청이 승인 없이 흘러가므로
     * 알릴 사람이 없다. 결재 권한 코드가 비었거나 코드 목록 밖이면(ApprovalAuthorityPolicy가 «승인
     * 정책이 깨진 상태»로 다루는 자리) 아무에게도 가지 않는다 — 승인 버튼도 아무에게도 안 보인다.
     *
     * 승인·반려 → 담당자 한 사람.
     */
    private Set<Long> recipientsOf(NotificationType type, SubWorkEntity subWork) {
        Set<Long> recipients = new LinkedHashSet<>();
        if (type == NotificationType.APPROVAL_REQUESTED) {
            SubWorkTypeEntity subWorkType = subWork.getSubWorkType();
            if (!subWorkType.isApprovalNeeded()) {
                return recipients;
            }
            AuthorityCode.fromSubWorkApproverCode(subWorkType.getAuthorizerAuthorityCode())
                    .ifPresent(
                            authority ->
                                    recipients.addAll(
                                            authorityPolicy.memberIdsWithAuthority(authority)));
            return recipients;
        }
        recipients.add(subWork.getOperation().getPersonInCharge().getId());
        return recipients;
    }
}
