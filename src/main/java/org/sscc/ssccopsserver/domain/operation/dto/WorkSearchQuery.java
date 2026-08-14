package org.sscc.ssccopsserver.domain.operation.dto;

import org.sscc.ssccopsserver.domain.operation.entity.WorkStatus;
import org.sscc.ssccopsserver.domain.operation.entity.WorkType;

/*
 * 해석이 끝난 상위 업무 목록 조회 조건 (OPS-020). Repository는 이 값만 보고 쿼리를 만든다.
 *
 * 요청 DTO(WorkSearchCondition)를 그대로 넘기지 않는 것은 그쪽이 아직 문자열이기 때문이다.
 * 기준 코드 위반·커서 해독 실패는 요청을 받는 자리에서 한 번에 걸러야 하고, 그 판정이
 * Repository까지 흘러 들어가면 조회 코드가 400을 던지게 된다 (LY-02).
 *
 * 하위 업무 쪽(SubWorkSearchQuery)과 달리 now가 없다. 지연·마감임박처럼 '지금'을 기준으로
 * 하는 조건이 상위 업무에는 없어서다 — 마감 컬럼 자체가 work에 없다.
 */
public record WorkSearchQuery(
        WorkStatus workStatus, WorkType workType, int size, WorkSortOrder sort, WorkCursor cursor) {

    public boolean hasWorkStatusFilter() {
        return workStatus != null;
    }

    public boolean hasWorkTypeFilter() {
        return workType != null;
    }

    public boolean hasCursor() {
        return cursor != null;
    }

    // hasNext 판정용으로 한 건 더 읽는다. 마지막 페이지인지 알려면 다음 건의 존재만 보면 된다
    public int fetchSize() {
        return size + 1;
    }
}
