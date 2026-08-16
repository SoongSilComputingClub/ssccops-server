package org.sscc.ssccopsserver.domain.operation.entity;

/*
 * mtg_dtl.prcs_se_cd — 안건 처리 구분. 프론트 codes.ts의 PrcsSeCd와 이름을 맞춘다.
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
