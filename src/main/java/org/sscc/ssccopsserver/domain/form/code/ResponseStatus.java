package org.sscc.ssccopsserver.domain.form.code;

/*
 * form_rspns_hstry.rspns_stts_cd — 폼 응답 상태 기준 코드.
 *
 * DRAFT가 들어 있는 것은 ssccops #64에서 확정된 결과다. 응답 자동 저장(#36)이 제출 전
 * 내용을 어딘가에 담아야 하는데, 임시저장 전용 테이블을 따로 두면 제출 시점에 행을 옮겨야
 * 하고 "한 회원이 한 폼에 하나"라는 UNIQUE 제약을 두 테이블에 걸쳐 지켜야 한다.
 * 같은 행의 상태만 바꾸는 편이 단순해서 상태 어휘에 DRAFT를 넣었다.
 *
 * 그 결정의 직접적인 귀결이 sbmsn_dt(제출 일시) nullable이다 — DRAFT인 응답은 아직
 * 제출되지 않았으므로 제출 일시가 존재할 수 없다. 두 사실은 같이 움직인다.
 *
 * ACCEPTED·REJECTED로의 전이는 응답 상태 변경 API(#37)의 범위라 여기서 규칙을 만들지 않는다.
 */
public enum ResponseStatus {

    /** 임시저장. 아직 제출되지 않았고 sbmsn_dt가 NULL인 유일한 상태 (#36) */
    DRAFT,

    /** 응답자가 제출을 마친 상태. 심사 전 기본값 */
    SUBMITTED,

    /** 운영진이 승인 */
    ACCEPTED,

    /** 운영진이 반려 */
    REJECTED;

    public String code() {
        return name();
    }
}
