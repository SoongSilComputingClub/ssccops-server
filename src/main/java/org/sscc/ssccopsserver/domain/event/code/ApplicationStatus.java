package org.sscc.ssccopsserver.domain.event.code;

import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;

/*
 * 신청자가 보는 내 신청 상태 (ssccops#145 · GET /v1/events/my-applications).
 *
 * **저장되는 값이 아니라 파생 값이다.** 사실은 두 테이블에 흩어져 있다 — 심사 결과는
 * form_rspns_hstry.rspns_stts_cd에, 참가 확정·대기·취소는 event_ptcp.ptcp_stts_cd에 있다.
 * 신청자 화면이 보여줄 것은 "내 신청이 지금 어떤 상태인가" 하나이므로, 그 둘을 한 어휘로
 * 접는 규칙이 필요하고 그 규칙의 유일한 구현이 of(...)다.
 *
 * ── 참가자 명단이 응답 상태를 이긴다 ─────────────────────────
 * 참가자 등록(#158)은 심사가 끝난 뒤(ACCEPTED) 일어난다 — 그것이 운영자의 순서다. 그래서
 * 명단에 행이 있다는 것은 그 신청의 심사가 이미 끝났다는 뜻이고, 응답 상태를 우선하면
 * 확정된 사람에게 "승인됨"만 보인다(더 나중에 일어난 사실을 더 이른 사실이 가린다).
 * 반대로 명단 행이 없으면 참가 상태를 지어낼 수 없으므로 응답 상태를 쓴다.
 *
 * 파생을 서비스의 if로 흩지 않는 것은 ResponseStatus.submittedOrLater()가 코드 enum으로
 * 옮겨 간 것과 같은 이유다 — 화면·경로가 늘 때마다 같은 표가 복제되면 갈린다 (LY-02).
 *
 * ── 어휘는 웹과의 계약이다 ──────────────────────────────
 * 값을 더하는 것은 계약 변경이다(oasdiff는 응답 enum 추가를 WARN으로 통과시키지만 웹이 모르는
 * 값은 배지 없는 카드가 된다 — 웹 #628과 함께 간다). 수정요청(ResponseStatus.CHANGES_REQUESTED)은
 * 처음엔 SUBMITTED로 접었다 — 응답자가 사유를 보고 다시 내는 화면이 없어서(#141이 미뤘다). 그
 * 화면이 ssccops#221에서 생겼고, 접힌 채로는 «내 활동 › 신청한 행사»에서 수정 요청이 «제출됨»으로만
 * 보여 응답자가 다시 낼 것이 있는 줄 몰랐다(ssccops#458). 그래서 **그대로 통과**시킨다 — 명단 행이
 * 있으면 여전히 참가 상태가 이긴다(위 절).
 */
public enum ApplicationStatus {

    /** 제출했고 심사가 끝나지 않았다 */
    SUBMITTED,

    /** 운영진이 수정을 요청했다 — 응답자가 고쳐서 다시 내야 한다 (ssccops#458) */
    CHANGES_REQUESTED,

    /** 심사 승인. 아직 명단에 오르지는 않았다 — 올랐다면 CONFIRMED·WAITLISTED가 된다 */
    ACCEPTED,

    /** 심사 반려. 응답자에게 종결이다 (#141) */
    REJECTED,

    /** 참가 확정 */
    CONFIRMED,

    /** 대기. **순번은 싣지 않는다** — 신청자에게 비공개다 (wave2 D5) */
    WAITLISTED,

    /** 확정 후 취소. 운영자만 할 수 있다 (D14) */
    CANCELLED;

    /*
     * 파생 규칙의 유일한 구현. participantStatus가 null이면(명단에 없으면) 응답 상태를 쓴다.
     */
    public static ApplicationStatus of(
            EventParticipantStatus participantStatus, ResponseStatus responseStatus) {
        return participantStatus != null
                ? fromParticipantStatus(participantStatus)
                : fromResponseStatus(responseStatus);
    }

    private static ApplicationStatus fromParticipantStatus(EventParticipantStatus status) {
        return switch (status) {
            case CONFIRMED -> CONFIRMED;
            case WAITLISTED -> WAITLISTED;
            case CANCELLED -> CANCELLED;
        };
    }

    /*
     * DRAFT는 여기 닿을 수 없다 — 제출 전 초안은 신청이 아니라서 조회 질의가
     * ResponseStatus.submittedOrLater()로 이미 걸러 낸다. 조용히 다른 값으로 접지 않고 터뜨리는
     * 것은, 접으면 초안이 "제출됨"으로 목록에 나타나는 것이 버그가 아니라 정상 동작처럼 보이기
     * 때문이다.
     */
    private static ApplicationStatus fromResponseStatus(ResponseStatus status) {
        return switch (status) {
            case SUBMITTED -> SUBMITTED;
            case CHANGES_REQUESTED -> CHANGES_REQUESTED;
            case ACCEPTED -> ACCEPTED;
            case REJECTED -> REJECTED;
            case DRAFT ->
                    throw new IllegalArgumentException(
                            "DRAFT 응답은 신청이 아니다 — 조회 질의가 submittedOrLater()로 걸러야 한다");
        };
    }

    public String code() {
        return name();
    }
}
