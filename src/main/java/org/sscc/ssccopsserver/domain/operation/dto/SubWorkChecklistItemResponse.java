package org.sscc.ssccopsserver.domain.operation.dto;

import org.sscc.ssccopsserver.domain.operation.entity.SubWorkChecklistItemEntity;

/*
 * 하위 업무 완료 체크리스트 항목 (OPS-007 응답에 포함).
 * 등록 직후에는 모두 미완료이며, 체크 처리는 OPS-012·013이 맡는다.
 */
public record SubWorkChecklistItemResponse(
        Long checklistItemId, String article, boolean isCompleted, Integer sortOrder) {

    public static SubWorkChecklistItemResponse from(SubWorkChecklistItemEntity item) {
        return new SubWorkChecklistItemResponse(
                item.getId(), item.getArticle(), item.isCompleted(), item.getSortOrder());
    }
}
