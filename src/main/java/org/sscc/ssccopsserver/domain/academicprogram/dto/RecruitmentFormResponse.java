package org.sscc.ssccopsserver.domain.academicprogram.dto;

import org.sscc.ssccopsserver.domain.form.dto.FormDetailResponse;

/*
 * 모집 폼 조회·저장 응답 (#483 · GET·PUT /v1/academic-programs/{id}/recruitment/form).
 *
 * 폼 상세를 **그대로 품는다.** 문항 편집기가 이미 FormDetailResponse를 초안으로 받아 쓰는
 * 계약이라(어드민 폼 편집 화면), 여기서 모양을 새로 만들면 lms 편집기가 같은 문항을 다른
 * 타입으로 다시 정의하게 된다 — 한쪽에만 필드가 늘면 조용히 갈린다.
 *
 * isEditable은 서버 판정이다(CurriculumItemWithSessionResponse.isEditable과 같은 자리) —
 * 화면이 formReceiptStatus를 보고 다시 계산하면 버튼이 켜져 있는데 저장은 409인 구간이 생긴다.
 * 요청자가 리더인지까지 접은 값이 아니라 **창이 열려 있는가**만 본다: 이 경로는 리더 또는
 * 학술국장만 도달하고, 자격이 없으면 여기까지 오지 않는다(403).
 *
 * **마감 시각을 따로 싣지 않는다.** 창이 닫히는 시각은 form.rcptBgngDt이고 그 값은 이미 form
 * 안에 있다 — 여기 editableUntil을 두면 같은 사실이 한 응답에 두 번 실린다. 화면은
 * isEditable && form.rcptBgngDt != null일 때만 "남은 시간"을 그리고, rcptBgngDt가 null인
 * 편집 가능 상태는 "아직 모집 일정이 정해지지 않았다"는 뜻이다.
 */
public record RecruitmentFormResponse(FormDetailResponse form, boolean isEditable) {

    public static RecruitmentFormResponse of(FormDetailResponse form, boolean editable) {
        return new RecruitmentFormResponse(form, editable);
    }
}
