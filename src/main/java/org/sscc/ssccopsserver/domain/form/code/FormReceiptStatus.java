package org.sscc.ssccopsserver.domain.form.code;

/*
 * 화면이 보여주는 접수 상태 (#33). form_stts_cd와 접수 기간을 함께 본 결과이며 DB 컬럼도
 * 기준 코드 테이블도 아니다 — 그래서 이름에 `Cd` 접미를 붙이지 않았다. 저장하지 않고 조회할
 * 때마다 주입된 Clock으로 다시 계산한다 (FormReceiptPolicy).
 *
 * 이 enum이 존재하는 이유는 접수 기간이 지난 폼이 자동으로 CLOSED가 되지 않기 때문이다.
 * 실제 응답은 FormReceiptPolicy가 시간까지 보고 막지만, form_stts_cd만 읽는 목록에서는
 * 그 폼이 여전히 '접수 중'으로 보인다. 그 간극을 배치로 상태를 덮어써서 메우지 않고
 * 표시 계층에서 나누기로 한 결정의 결과물이다 (근거는 FormReceiptPolicy 주석).
 *
 * 웹(ssccops-web #9)은 formSttsCd가 아니라 이 값으로 배지를 그린다.
 */
public enum FormReceiptStatus {

    /** 작성 중. 공개 링크로 접근할 수 없다 — form_stts_cd = DRAFT */
    DRAFT,

    /** 접수 예정. 열려 있지만 아직 시작 일시 전이다 */
    SCHEDULED,

    /** 접수 중. 지금 응답을 받을 수 있는 유일한 상태다 */
    ACCEPTING,

    /** 기간 종료. form_stts_cd는 아직 OPEN이지만 종료 일시가 지나 응답을 받지 않는다 */
    EXPIRED,

    /** 마감. 운영자가 직접 닫았다 — form_stts_cd = CLOSED */
    CLOSED
}
