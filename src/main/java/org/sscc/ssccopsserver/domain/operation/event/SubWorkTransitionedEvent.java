package org.sscc.ssccopsserver.domain.operation.event;

import org.sscc.ssccopsserver.domain.operation.entity.TransitionAction;

/*
 * 하위 업무 전이가 일어났다 (ssccops#446 · ADR-0045).
 *
 * `SubWorkServiceImpl.transitionSubWork`가 `ApplicationEventPublisher`로 알리고 알림 도메인이
 * `@TransactionalEventListener(AFTER_COMMIT)`로 듣는다. **운영 도메인은 누가 듣는지 모른다** —
 * 이 record는 운영 패키지의 것이고 알림을 import하지 않는다(`DomainCycleTest`).
 *
 * 이 저장소의 도메인 간 연동은 원래 포트 인터페이스다(AGENTS.md — `SystemFormApprovalHook`이
 * 이벤트를 기각한 이유는 «승인과 이관은 한 트랜잭션»이라는 원자성이었다). 여기는 **그 반대**다:
 * 알림은 전이를 절대 막으면 안 되고(푸시 서비스가 느리거나 죽어도 승인 버튼은 눌려야 한다), 알림
 * 행이 롤백된 전이를 가리키면 안 된다. 그래서 커밋 뒤에 별도 트랜잭션·별도 스레드로 듣는 이벤트가
 * 맞고, 그 대가(프로세스가 죽는 순간의 알림을 잃는다)는 ADR-0045가 감수했다.
 *
 * 싣는 것은 식별자 셋뿐이다. 엔티티를 실으면 듣는 쪽이 다른 트랜잭션·스레드에서 지연 로딩을 건드려
 * `LazyInitializationException`이 되고, 스냅샷을 실으면 두 도메인이 같은 DTO를 공유하게 된다 —
 * 듣는 쪽이 자기 트랜잭션에서 다시 읽는다.
 *
 * @param subWorkId 전이된 하위 업무
 * @param action 어느 전이였나 — 수신자와 문구가 이것으로 갈린다
 * @param performerId 전이를 수행한 회원. 수신자에서 뺀다(자기가 한 일을 자기에게 알리지 않는다)
 */
public record SubWorkTransitionedEvent(Long subWorkId, TransitionAction action, Long performerId) {}
