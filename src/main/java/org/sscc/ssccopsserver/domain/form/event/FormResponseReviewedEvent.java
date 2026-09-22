package org.sscc.ssccopsserver.domain.form.event;

import org.sscc.ssccopsserver.domain.form.code.ResponseReviewAction;

/*
 * 폼 응답이 검토됐다 — 승인·반려·수정 요청 (#528 · ssccops#453 · ADR-0045).
 *
 * `FormResponseServiceImpl.reviewResponse`가 `ApplicationEventPublisher`로 알리고 알림 도메인이
 * `@TransactionalEventListener(AFTER_COMMIT)`로 듣는다. **폼 도메인은 누가 듣는지 모른다** — 이
 * record는 폼 패키지의 것이고 알림을 import하지 않는다(`DomainCycleTest`).
 *
 * 왜 포트가 아니라 이벤트인지는 `SubWorkTransitionedEvent`와 같다 — 알림은 검토를 절대 막으면 안
 * 되고, 롤백된 검토의 알림이 남으면 안 된다. 같은 트랜잭션 안에서 도는 `SystemFormApprovalHook`
 * (기획안 승인 → 학술 이관)과는 층이 다르다: 그쪽은 «승인과 이관은 하나»라 원자성이 필요하고,
 * 알림은 커밋 뒤의 일이다.
 *
 * 싣는 것은 식별자 셋뿐이다(엔티티를 실으면 다른 스레드에서 `LazyInitializationException`).
 * 제출(`SUBMIT`)은 이 이벤트로 나가지 않는다 — 응답자가 한 일을 응답자에게 알릴 이유가 없다.
 *
 * @param formResponseId 검토된 응답(form_rspns_hstry)
 * @param action 승인·반려·수정 요청 중 무엇이었나 — 알림 종류와 문구가 이것으로 갈린다
 * @param reviewerId 검토한 회원. 응답자와 같으면 알림이 없다(자기 응답을 자기가 검토한 경우)
 */
public record FormResponseReviewedEvent(
        Long formResponseId, ResponseReviewAction action, Long reviewerId) {}
