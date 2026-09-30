package org.sscc.ssccopsserver.domain.academicprogram.dto;

import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramStatus;
import org.sscc.ssccopsserver.domain.form.code.FormReceiptStatus;

/*
 * 학술 활동 상태 전이 응답 (#133).
 *
 * leadrMbrId는 싣지 않는다(계약표에서 뺀 값) — 생성 시점부터 항상 존재해 전이 응답이 새로
 * 알려줄 것이 없다(재설계 전에는 APPROVE 전이가 리더를 처음 확정해 응답에 실었었다).
 *
 * formReceiptStatus는 **이 전이가 폼을 바꿨을 때만** 값이 있다 — 바꾼 직후의 파생 접수
 * 상태(FormReceiptPolicy)를 그대로 실어, 화면이 전이 직후 폼을 다시 조회하지 않고 배지를 그릴
 * 수 있게 한다.
 *
 *   START_RECRUITMENT  폼을 OPEN 전이한다                → 언제나 값이 있다
 *   APPROVE_COMPLETION 접수 중(OPEN)인 폼만 CLOSE 한다    → 닫았으면 CLOSED, 아니면 NULL (#597)
 *   REOPEN             폼을 다시 열지 않는다              → 언제나 NULL (#597 · ADR-0057)
 *   DISCONTINUE        접수 중(OPEN)인 폼만 CLOSE 한다    → 닫았으면 CLOSED, 아니면 NULL (#611)
 *   REINSTATE          폼을 다시 열지 않는다              → 언제나 NULL (#611 · ADR-0058)
 *
 * afterSttsCd는 REINSTATE에서 폐지 전 상태(APPROVED 또는 ONGOING)다 — 표가 아니라 폐지 이력이
 * 정한 값이라 화면은 이 필드를 그대로 읽는다.
 */
public record AcademicProgramTransitionResponse(
        Long academicProgramId,
        AcademicProgramStatus beforeSttsCd,
        AcademicProgramStatus afterSttsCd,
        FormReceiptStatus formReceiptStatus) {

    public static AcademicProgramTransitionResponse of(
            Long academicProgramId,
            AcademicProgramStatus beforeSttsCd,
            AcademicProgramStatus afterSttsCd,
            FormReceiptStatus formReceiptStatus) {
        return new AcademicProgramTransitionResponse(
                academicProgramId, beforeSttsCd, afterSttsCd, formReceiptStatus);
    }
}
