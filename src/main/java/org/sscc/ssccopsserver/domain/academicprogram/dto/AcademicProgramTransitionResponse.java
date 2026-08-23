package org.sscc.ssccopsserver.domain.academicprogram.dto;

import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramStatus;
import org.sscc.ssccopsserver.domain.form.code.FormReceiptStatus;

/*
 * 학술 활동 상태 전이 응답 (#133).
 *
 * leadrMbrId는 싣지 않는다(계약표에서 뺀 값) — 생성 시점부터 항상 존재해 전이 응답이 새로
 * 알려줄 것이 없다(재설계 전에는 APPROVE 전이가 리더를 처음 확정해 응답에 실었었다).
 *
 * formReceiptStatus는 START_RECRUITMENT에서만 값이 있다 — 연결된 Form을 OPEN 전이한 직후의
 * 파생 접수 상태(FormReceiptPolicy)를 그대로 실어, 화면이 전이 직후 폼을 다시 조회하지 않고
 * "모집 중" 배지를 그릴 수 있게 한다. APPROVE_COMPLETION은 폼을 건드리지 않으므로 NULL이다.
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
