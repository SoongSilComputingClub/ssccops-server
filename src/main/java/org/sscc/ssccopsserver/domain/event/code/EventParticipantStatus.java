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

    /*
     * 대기 — 결원 시 운영자가 수동 승격한다. 순번은 신청자에게 비공개다 (D5).
     *
     * 확정에서 내려온 자리이기도 하다 (#198 · 강등). 어디서 왔는지는 이 값이 구별하지 않는다 —
     * 명단이 답하는 것은 "지금 이 사람이 참가자인가 대기자인가" 하나이고, 오간 자취는 mdfcn_dt와
     * 전이 요청이 남긴다.
     */
    WAITLISTED,

    /** 취소 — 확정 후 취소. 운영자만 할 수 있다 (D14). 행을 지우지 않는 것은 영구 보존(D16) 때문이다 */
    CANCELLED;

    /*
     * 등록으로 도달할 수 있는 상태인가 (ssccops#146).
     *
     * 취소는 등록의 결과가 아니라 확정된 참가자에게 일어나는 일이다 — 취소로 시작하는 행을
     * 허용하면 "참가자였던 적이 없는 취소자"가 명단에 쌓이고, 그 행이 무엇을 뜻하는지 아무도
     * 답할 수 없다. 판단을 여기 두는 것은 등록 경로가 늘어도 규칙이 한 벌로 남게 하기 위해서다.
     */
    public boolean isRegistrable() {
        return this == CONFIRMED || this == WAITLISTED;
    }
}
