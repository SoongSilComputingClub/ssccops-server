package org.sscc.ssccopsserver.domain.event.code;

/*
 * 행사 저장 상태 (ssccops#133 · D9). event.event_stts_cd에 문자열로 저장된다.
 *
 * 저장 상태는 이 셋뿐이다. 예정/진행중/종료는 상태가 아니라 행사 일시(event_bgng_dt·
 * event_end_dt)에서 조회 시점에 파생하는 값이고, 모집 중/마감은 연결된 폼의 receiptStatus가
 * 준다(D3) — 같은 사실을 두 곳에 저장하면 반드시 어긋난다. 폼의 FormStatus/FormReceiptStatus
 * 분리와 같은 패턴이다.
 *
 * 전이표(DRAFT↔PUBLISHED→ARCHIVED→PUBLISHED)와 전이 검증은 행사 CRUD(ssccops#139)의 몫이라
 * 여기 두지 않는다 — 셋업 단계에서 전이 규칙을 미리 굳히면 그 이슈의 결정 여지가 사라진다.
 */
public enum EventStatus {

    /** 작성 중 — 기본값. 공개 목록·상세에 노출되지 않는다 */
    DRAFT,

    /** 게시 — 공개 앱에 노출된다. 게시는 폼 연결·폼 상태와 독립이다 (D11) */
    PUBLISHED,

    /** 보관 — 공개 목록에서 내려간다(상세 404). 재공개할 수 있다 */
    ARCHIVED
}
