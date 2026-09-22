package org.sscc.ssccopsserver.domain.event.event;

import org.sscc.ssccopsserver.domain.event.code.EventParticipantStatus;

/*
 * 행사 참가 상태가 바뀌었다 — 확정·대기·취소 (#528 · ssccops#453 · ADR-0045).
 *
 * `EventParticipationServiceImpl`이 명단 행의 상태를 실제로 바꾼 자리마다 `ApplicationEventPublisher`로
 * 알리고 알림 도메인이 `@TransactionalEventListener(AFTER_COMMIT)`로 듣는다. **행사 도메인은 누가
 * 듣는지 모른다** — 이 record는 행사 패키지의 것이고 알림을 import하지 않는다(`DomainCycleTest`).
 * 왜 포트가 아니라 이벤트인지는 `SubWorkTransitionedEvent`의 주석에 있다.
 *
 * 발행하는 자리는 셋이다 — 전이(`changeParticipantStatus`) · 선발 다시 저장에서 값이 달라진 줄
 * (`registerOrUpdateParticipant`) · **명단에 처음 오르는 등록**(`register`, `previous == null`).
 * 등록을 «변경»에 넣은 것은 신청자 입장에서 확정·대기를 처음 아는 순간이 바로 그 등록이기
 * 때문이다 — 모집 선발이 명단을 만드는 길이 등록이라, 등록을 빼면 첫 확정은 아무에게도 가지 않고
 * 강등·승격만 간다. 같은 값으로의 재저장은 서비스가 `changeStatus`를 부르지 않으므로 여기까지
 * 오지 않는다(바뀐 것이 없으면 알릴 것도 없다).
 *
 * 싣는 것은 식별자와 코드값뿐이다(엔티티를 실으면 다른 스레드에서 `LazyInitializationException`).
 *
 * @param eventParticipantId 명단 행(event_ptcp)
 * @param previous 이전 상태. 처음 등록이면 null
 * @param next 도달한 상태 — 알림 종류가 이것으로 갈린다
 * @param performerId 바꾼 회원(운영자·등록자). 참가자 본인이면 알림이 없다
 */
public record EventParticipantStatusChangedEvent(
        Long eventParticipantId,
        EventParticipantStatus previous,
        EventParticipantStatus next,
        Long performerId) {}
