package org.sscc.ssccopsserver.domain.form.code;

import java.util.Optional;

/*
 * form_rspns_rvw_hstry.rvw_prcs_se_cd — 폼 응답 검토 처리 구분 코드 (#141).
 *
 * 컬럼명이 일반명 prcs_se_cd였다가 '검토(rvw)'를 앞에 붙였다(#224 · ssccops#159). 데이터사전의
 * 표준코드는 코드그룹ID = 컬럼ID로 묶이는데 회의 안건(mtg_dtl)도 같은 이름의 컬럼을 쓰면서
 * 값 집합이 PENDING · HOLD · CLOSED로 전혀 달라, 아래 4종은 넣을 그룹이 없어 사전에 등재되지
 * 못하고 있었다. 한쪽만 한정어를 붙이면 남은 쪽이 일반명을 계속 점유해 다음 테이블에서 같은
 * 충돌이 반복되므로 회의 쪽도 agnd_prcs_se_cd로 함께 옮겼다. 두 컬럼은 뜻도 다르다 — 안건 쪽은
 * '지금 어떤 상태인가'이고 이쪽은 '그때 무슨 일이 있었는가'다.
 *
 * 응답 DTO의 필드명도 함께 prcsSeCd → rvwPrcsSeCd로 바뀐다(FormResponseReviewHistoryResponse).
 * 컬럼만 바꾸고 필드명을 남기지 않는 것은, 웹이 이미 이름 충돌을 자기 접두사(RspnsPrcsSeCd ·
 * RSPNS_PRCS_SE_NM)로 우회하고 있어 이름을 반만 고치면 그 우회가 영구화되기 때문이다.
 *
 * 이력 한 줄이 "누가 · 언제 · 무엇을 했는가"를 스스로 말하려면 그 '무엇'을 담을 어휘가 있어야
 * 한다. 결과 상태(rspns_stts_cd)를 그대로 베껴 두지 않는 것은 두 축의 뜻이 다르기 때문이다 —
 * 상태는 "지금 이 응답이 어디에 있는가"이고 처리 구분은 "그때 무슨 일이 있었는가"다. 실제로
 * SUBMIT은 결과 상태가 SUBMITTED로 같지만 검토자가 아니라 응답자가 한 일이며, 재제출까지
 * 세면 한 응답에 여러 번 나타난다.
 *
 * ── 검토자가 쓸 수 있는 것은 셋뿐이다 ──────────────────────────
 * SUBMIT은 응답자의 제출(FormResponseHistoryEntity.submit)이 남기는 행이라 검토 API로는 만들
 * 수 없다. 그래서 검토 요청이 SUBMITTED를 목표 상태로 보내면 옮겨 적을 처리 구분이 없다 —
 * 그 요청을 SUBMIT으로 기록하면 응답자가 한 제출을 검토자 이름으로 남기게 되고, 처리 구분을
 * 비운 채 기록하면 이력이 "무슨 일이 있었는지 모르는 행"이 된다. 어느 쪽도 이 이슈가 없애려던
 * 문제를 다시 만든다. 미심사(SUBMITTED)로 되돌아가는 길은 응답자의 재제출뿐이며, 결론을 낸
 * 뒤(ACCEPTED · REJECTED)에는 어느 처리도 다시 걸 수 없다 —
 * FormResponseHistoryEntity.changeStatus의 전이표가 그 이유를 갖는다.
 *
 * ── 검토 의견 필수 여부도 여기가 갖는다 ────────────────────────
 * 수정요청·반려는 응답자에게 무엇을 하라는 통보라 사유 없이 성립하지 않고(sub_work_rjct의
 * VR-O06과 같은 판단), 승인은 통보할 것이 없어 선택이다. 이 판단을 서비스의 if로 옮기면
 * 검토를 기록하는 경로가 늘 때마다 규칙이 복제된다 (LY-02) — 실제로 막는 자리는
 * FormResponseReviewHistoryEntity.record 하나다.
 *
 * FormStatusAction과 같은 방침으로 **판단만 하고 오류는 던지지 않는다**. 던지는 자리는 엔티티다.
 */
public enum ResponseReviewAction {

    /** 제출 — 응답자가 냈다. 최초 제출과 재제출 모두 이 코드이며 sbmsn_seq로 갈린다 */
    SUBMIT(ResponseStatus.SUBMITTED, false),

    /** 승인 — 검토 의견은 선택이다 */
    ACCEPT(ResponseStatus.ACCEPTED, false),

    /** 수정요청 — 무엇을 고쳐야 하는지 적지 않으면 응답자가 할 수 있는 일이 없다 */
    REQUEST_CHANGES(ResponseStatus.CHANGES_REQUESTED, true),

    /** 반려 — 응답자에게 종결이라 사유가 더 중요하다 */
    REJECT(ResponseStatus.REJECTED, true);

    private final ResponseStatus resultStatus;
    private final boolean opinionRequired;

    ResponseReviewAction(ResponseStatus resultStatus, boolean opinionRequired) {
        this.resultStatus = resultStatus;
        this.opinionRequired = opinionRequired;
    }

    /*
     * 검토 요청이 보낸 목표 상태를 처리 구분으로 옮긴다. 검토로 도달할 수 없는 상태(DRAFT ·
     * SUBMITTED)면 비어 있다 — 거절은 호출부(FormResponseHistoryEntity.review)의 몫이다.
     */
    public static Optional<ResponseReviewAction> reviewTargetOf(ResponseStatus targetStatus) {
        return switch (targetStatus) {
            case ACCEPTED -> Optional.of(ACCEPT);
            case CHANGES_REQUESTED -> Optional.of(REQUEST_CHANGES);
            case REJECTED -> Optional.of(REJECT);
            case DRAFT, SUBMITTED -> Optional.empty();
        };
    }

    public ResponseStatus resultStatus() {
        return resultStatus;
    }

    /** 검토 의견이 반드시 있어야 하는 처리인가. 막는 자리는 이력 엔티티다 */
    public boolean requiresOpinion() {
        return opinionRequired;
    }

    public String code() {
        return name();
    }
}
