package org.sscc.ssccopsserver.domain.operation.dto;

import org.sscc.ssccopsserver.domain.operation.entity.WorkTagEntity;

/*
 * 업무 목록 카드·상세에 실리는 태그 칩 (#624). 칩에는 이름과 식별자만 있으면 된다 — 일시·사용 건수는
 * 태그 관리 화면의 값이라 여기 싣지 않는다 (LY-03 · FormLabelSummaryResponse와 같다).
 */
public record WorkTagSummaryResponse(Long workTagId, String tagNm) {

    public static WorkTagSummaryResponse from(WorkTagEntity tag) {
        return new WorkTagSummaryResponse(tag.getId(), tag.getName());
    }
}
