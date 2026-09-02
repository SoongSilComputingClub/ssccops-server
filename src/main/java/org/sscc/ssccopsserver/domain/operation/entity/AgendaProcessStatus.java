package org.sscc.ssccopsserver.domain.operation.entity;

/*
 * mtg_dtl.agnd_prcs_se_cd — 안건 처리 구분. 프론트 codes.ts의 AgndPrcsSeCd와 이름을 맞춘다.
 *
 * 예전에는 이 컬럼명이 일반명 prcs_se_cd였고, 그것이 문제였다(#224 · ssccops#159).
 * 데이터사전의 표준코드는 **코드그룹ID = 컬럼ID**으로 묶이는데, 폼 응답 검토 이력
 * (form_rspns_rvw_hstry)도 같은 이름의 컬럼에 전혀 다른 어휘(SUBMIT · ACCEPT · REQUEST_CHANGES ·
 * REJECT)를 담고 있어, 한 그룹에 두 어휘를 섞을 수밖에 없었다 — 사전만 보고는 어느 값이 어느
 * 테이블 것인지 알 수 없고, 그 때문에 검토 쪽 4종은 아예 등재되지 못했다. 한쪽에만 한정어를
 * 붙이면 남은 쪽이 일반명을 계속 점유해 다음에 '처리 구분'이 필요한 테이블이 나올 때 같은 충돌이
 * 반복된다. 그래서 양쪽 다 자기 이름을 갖는다 — 안건은 agnd_prcs_se_cd, 검토는 rvw_prcs_se_cd다.
 * **바뀐 것은 컬럼명뿐이다** — 값 집합도 응답 필드명(processStatus)도 그대로라 이 개명으로 API
 * 계약은 움직이지 않는다(개명이 계약까지 건드리는 쪽은 폼 검토 이력 하나다).
 *
 * 회의 종료 전이(TR-M3)는 PENDING(미처리)이 남아 있으면 막는다. HOLD(보류)는 "다음 회의로
 * 이월하겠다"는 의사 표시라 종료를 막지 않는다 — 안건을 없애지 않고 종료하려면 보류로
 * 명시해야 한다.
 */
public enum AgendaProcessStatus {
    PENDING, // 미처리
    HOLD, // 보류
    CLOSED // 종료
}
