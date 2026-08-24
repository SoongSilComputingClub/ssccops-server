package org.sscc.ssccopsserver.domain.event.code;

/*
 * 행사 진행 단계 (ssccops#133 · D9). event 테이블에는 대응 컬럼이 없다 — 저장하지 않고
 * 행사 일시(event_bgng_dt·event_end_dt)에서 조회 시점에 파생한다 (EventPhasePolicy).
 *
 * 저장 상태(EventStatus)와 축이 다르다. DRAFT/PUBLISHED/ARCHIVED는 운영자가 바꾸는 값이고
 * 이쪽은 시간이 바꾸는 값이라, 컬럼으로 두면 "종료된 행사"를 만들 배치가 필요해지고 그 배치가
 * 폼 자동 마감을 두지 않은 이유(#33)와 똑같은 문제를 다시 만든다. 폼의
 * FormStatus/FormReceiptStatus 분리와 같은 패턴이다.
 */
public enum EventPhase {

    /** 아직 시작 전 — 시작 일시가 미래다 */
    UPCOMING,

    /** 진행 중 — 시작했고 (종료 일시가 없거나) 아직 끝나지 않았다 */
    ONGOING,

    /** 종료 — 종료 일시가 과거다 */
    ENDED,

    /** 일시 미정 — 시작·종료 일시가 둘 다 없다. 일시 없는 공지가 정상이라 별도 값이다 */
    NONE
}
