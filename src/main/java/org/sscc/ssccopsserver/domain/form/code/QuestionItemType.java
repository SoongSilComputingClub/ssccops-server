package org.sscc.ssccopsserver.domain.form.code;

/*
 * 문항 유형 기준 코드. 다른 코드와 달리 테이블 컬럼이 아니라 form.qitem_cpst_cn(JSONB)
 * 안쪽의 qitemTypeCd 값이다 — 문항은 행이 아니라 JSON 문서로 저장되기 때문이다.
 *
 * 그래도 enum으로 굳히는 이유는, 이 값이 응답 검증 분기(필수 여부·정규식·최대 선택 수)를
 * 가르는 유일한 축이라서다. 문자열로 두면 오타가 저장 시점이 아니라 응답 검증 시점에
 * 조용히 드러난다. JSON 역직렬화 단계에서 기준 코드 밖의 값이 걸리는 편이 낫다.
 *
 * 어휘는 웹 shared/config/codes.ts의 QitemTypeCd와 같다.
 */
public enum QuestionItemType {

    /** 한 줄 텍스트. ptrnCn(정규식) 검증 대상 */
    SHORT_TEXT,

    /** 여러 줄 텍스트. ptrnCn(정규식) 검증 대상 */
    LONG_TEXT,

    /** 단일 선택. branchMap(선택지 → 이동할 페이지)을 가질 수 있는 유일한 유형 */
    SINGLE_CHOICE,

    /** 다중 선택. maxSlctCnt(최대 선택 수) 대상이며 응답이 문자열이 아니라 배열이다 */
    MULTI_CHOICE,

    /** 날짜 */
    DATE;

    public String code() {
        return name();
    }
}
