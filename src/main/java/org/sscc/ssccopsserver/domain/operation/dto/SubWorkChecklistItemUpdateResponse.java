package org.sscc.ssccopsserver.domain.operation.dto;

import org.sscc.ssccopsserver.domain.operation.entity.SubWorkChecklistItemEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkEntity;

/*
 * 완료 체크리스트 항목 체크·해제 응답 (OPS-013).
 *
 * 정의서가 명시한 응답은 갱신된 항목(ChecklistItem) 하나지만 checklistSummary를 함께 담는다.
 * 화면이 체크 직후 '2/4 완료' 표기를 다시 그려야 하는데, 세는 규칙을 클라이언트로 넘기면
 * 상세 조회(OPS-009)·상위 업무 상세(OPS-003)와 값이 어긋날 수 있다.
 *
 * 항목의 isDeletable은 체크 직후 뒤집힌다 (#307) — 체크하면 지울 수 없게 되고 풀면 다시
 * 지울 수 있다. 그 전환이 같은 응답에 실려야 화면이 체크박스와 삭제 버튼을 한 번에 다시 그린다.
 *
 * 업무 상태·승인 상태는 담지 않는다. 체크는 상태 전이가 아니라 스테퍼를 움직이지 않으며,
 * 상태가 바뀌는 것처럼 보이는 응답을 내려 화면이 오해하게 만들지 않는다.
 */
public record SubWorkChecklistItemUpdateResponse(
        Long subWorkId,
        SubWorkChecklistItemResponse item,
        SubWorkChecklistSummaryResponse checklistSummary) {

    public static SubWorkChecklistItemUpdateResponse of(
            SubWorkEntity subWork,
            SubWorkChecklistItemEntity item,
            SubWorkChecklistSummaryResponse checklistSummary) {
        return new SubWorkChecklistItemUpdateResponse(
                subWork.getId(),
                SubWorkChecklistItemResponse.from(item, subWork.isChecklistItemEditable()),
                checklistSummary);
    }
}
