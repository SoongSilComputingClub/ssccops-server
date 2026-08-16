package org.sscc.ssccopsserver.domain.form.code;

import java.util.EnumSet;

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
 * ACCEPTED·REJECTED로의 전이 규칙은 FormResponseHistoryEntity.changeStatus가 갖는다 (#37) —
 * 어떤 어휘가 있는지는 여기가, 그 사이를 어떻게 오갈 수 있는지는 엔티티가 정한다.
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

    /*
     * 제출 이상 — 응답자가 실제로 낸 응답의 상태들. 임시저장(DRAFT)만 빠진다.
     *
     * 폼 목록·상세의 응답 집계(#32)와 운영자용 응답 목록·심사(#37)가 같은 기준을 써야 해서 이
     * 어휘를 코드 enum이 갖는다. 원래는 FormServiceImpl의 private 상수였는데, #37이 같은 집합을
     * 필요로 하면서 서비스 두 곳에 같은 EnumSet이 놓일 참이었다 — 두 벌이 되면 갈리고, 갈리면
     * "응답 3건"인데 목록에는 1건만 보이는 상태가 된다.
     *
     * 반대로 문항 식별자 보호(existsByForm)는 DRAFT를 **포함한다**. 기준이 다르므로 그쪽은 이
     * 집합을 쓰지 않는다.
     *
     * 매번 새로 만드는 것은 EnumSet이 가변이기 때문이다. 상수로 두면 호출부가 add/remove로
     * 전역 기준을 조용히 바꿀 수 있다.
     */
    public static EnumSet<ResponseStatus> submittedOrLater() {
        return EnumSet.of(SUBMITTED, ACCEPTED, REJECTED);
    }

    public String code() {
        return name();
    }
}
