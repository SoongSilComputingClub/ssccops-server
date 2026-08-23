package org.sscc.ssccopsserver.domain.event.code;

/*
 * 참가자 명단 상태 (ssccops#133 · D5·D14). event_ptcp.ptcp_stts_cd에 문자열로 저장된다.
 *
 * REJECTED가 없는 것은 의도된 것이다 — 거절은 폼 응답 심사(ResponseStatus.REJECTED)에 남고,
 * 명단에는 확정·대기·(확정 후) 취소만 올라간다. 명단은 "참가(예정)자 목록"이지 심사 기록이
 * 아니다. 대기 상태의 본인 철회는 상태 전이가 아니라 행 삭제로 다룬다(D14) — 그 사람은
 * 참가자였던 적이 없다.
 */
public enum EventParticipantStatus {

    /** 확정 — 운영자가 확정 등록했거나 대기에서 수동 승격됐다. 정원 초과는 경고만 한다 (D5) */
    CONFIRMED,

    /** 대기 — 결원 시 운영자가 수동 승격한다. 순번은 신청자에게 비공개다 (D5) */
    WAITLISTED,

    /** 취소 — 확정 후 취소. 운영자만 할 수 있다 (D14). 행을 지우지 않는 것은 영구 보존(D16) 때문이다 */
    CANCELLED
}
