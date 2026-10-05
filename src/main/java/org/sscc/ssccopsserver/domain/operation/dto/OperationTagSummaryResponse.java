package org.sscc.ssccopsserver.domain.operation.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.sscc.ssccopsserver.domain.operation.entity.OperationTagEntity;
import org.sscc.ssccopsserver.domain.operation.entity.OperationTagRelationEntity;

/*
 * 업무·하위 업무·회의의 목록 행·상세에 실리는 태그 칩 (#637). 칩에는 이름과 식별자만 있으면 된다 —
 * 일시·사용 건수는 태그 관리 화면의 값이라 여기 싣지 않는다 (LY-03 · FormLabelSummaryResponse와 같다).
 * 식별자 이름이 관리 목록(OperationTagResponse)과 같은 operationTagId라 칩에서 고른 id를 그대로
 * tagId 필터에 넣는다.
 */
public record OperationTagSummaryResponse(Long operationTagId, String tagNm) {

    public static OperationTagSummaryResponse from(OperationTagEntity tag) {
        return new OperationTagSummaryResponse(tag.getId(), tag.getName());
    }

    /*
     * OperationRepository.findTagRelationsByOperationIds의 결과를 운영 건별로 나눈다. 세 서비스가 같은
     * 모양으로 나눠야 하므로 한 곳에 둔다. 순서는 쿼리가 정한 이름 오름차순을 그대로 지킨다
     * (LinkedHashMap · 각 목록은 삽입 순서). 태그가 없는 운영 건은 키가 없다 — 호출부가 빈 배열로 본다.
     */
    public static Map<Long, List<OperationTagSummaryResponse>> groupByOperationId(
            List<OperationTagRelationEntity> relations) {
        Map<Long, List<OperationTagSummaryResponse>> tagsByOperationId = new LinkedHashMap<>();
        for (OperationTagRelationEntity relation : relations) {
            tagsByOperationId
                    .computeIfAbsent(relation.getOperation().getId(), id -> new ArrayList<>())
                    .add(from(relation.getTag()));
        }
        return tagsByOperationId;
    }
}
