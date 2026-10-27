package org.sscc.ssccopsserver.domain.academicprogram.dto;

import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.form.code.FormReceiptStatus;

/*
 * 스터디장이 고치는 행사 게시물 (ssccops#618 · ADR-0069 참고 · GET · PUT
 * /v1/academic-programs/{id}/event-post).
 *
 * 행사 상세(EventDetailResponse)를 품지 않고 네 칸만 싣는다 — 이 경로가 고칠 수 있는 칸이 넷뿐이고,
 * 상세를 통째로 내리면 화면이 기간 · 정원 · 분류도 고칠 수 있는 것처럼 그리게 된다(받지 않는 값을
 * 보여 주는 편집기). 필드 이름은 행사 저장 요청(EventSaveRequest)과 같아 두 편집기가 같은 말을 쓴다.
 *
 * isEditable · lockReason은 서버 판정이다 — 화면이 receiptStatus를 보고 다시 계산하면 버튼은 켜져 있는데
 * 저장은 409인 구간이 생긴다(RecruitmentFormResponse.isEditable과 같은 자리). 이 경로는 리더만 닿으므로
 * 자격을 곱하지 않는다. receiptStatus는 연결된 모집 폼의 FormReceiptPolicy 판정 그대로다.
 */
public record AcademicProgramEventPostResponse(
        Long eventId,
        String eventTtl,
        String mtxtCn,
        String thmbUrlAddr,
        String plcNm,
        FormReceiptStatus receiptStatus,
        boolean isEditable,
        AcademicProgramEventPostLockReason lockReason) {

    public static AcademicProgramEventPostResponse of(
            EventEntity event,
            FormReceiptStatus receiptStatus,
            AcademicProgramEventPostLockReason lockReason) {
        return new AcademicProgramEventPostResponse(
                event.getId(),
                event.getTitle(),
                event.getContentMarkdown(),
                event.getThumbnailUrlAddress(),
                event.getPlaceName(),
                receiptStatus,
                lockReason == null,
                lockReason);
    }
}
