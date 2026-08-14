package org.sscc.ssccopsserver.domain.operation.dto;

import org.sscc.ssccopsserver.domain.operation.entity.WorkEntity;

/*
 * 하위 업무 목록(OPS-008)의 '상위 업무' 칸. 배지에 찍을 제목과 상위 업무 상세(OPS-003)로
 * 넘어갈 식별자만 담는다.
 *
 * 제목은 work가 아니라 그 oper가 갖는다 — work는 oper의 확장 테이블이라 제목·기간·담당자가
 * 모두 공통 속성 쪽에 있다.
 */
public record WorkSummaryResponse(Long workId, String title) {

    public static WorkSummaryResponse from(WorkEntity work) {
        return work == null
                ? null
                : new WorkSummaryResponse(work.getId(), work.getOperation().getTitle());
    }
}
