package org.sscc.ssccopsserver.domain.operation.dto;

import org.sscc.ssccopsserver.domain.operation.entity.SubWorkChecklistItemEntity;

/*
 * 하위 업무 완료 체크리스트 항목 (OPS-007 응답에 포함).
 * 등록 직후에는 모두 미완료이며, 체크 처리는 OPS-012·013이 맡는다.
 *
 * isDeletable은 지금 이 항목을 지울 수 있는지다 (#307). **항목마다 갈린다** — 같은 목록
 * 안에서도 체크된 항목은 false고 체크 안 된 항목은 true다. 검토 이후에는 전부 false로,
 * 상세 응답의 isChecklistItemEditable과 같은 잠금을 이미 반영하고 있다 — 화면은 둘을 AND로
 * 엮지 말고 이 값 하나로 삭제 버튼을 그린다 (판정은 SubWorkChecklistItemEntity.isDeletable).
 */
public record SubWorkChecklistItemResponse(
        Long checklistItemId,
        String article,
        boolean isCompleted,
        Integer sortOrder,
        boolean isDeletable) {

    public static SubWorkChecklistItemResponse from(
            SubWorkChecklistItemEntity item, boolean checklistItemEditable) {
        return new SubWorkChecklistItemResponse(
                item.getId(),
                item.getArticle(),
                item.isCompleted(),
                item.getSortOrder(),
                item.isDeletable(checklistItemEditable));
    }
}
