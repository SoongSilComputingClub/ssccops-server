package org.sscc.ssccopsserver.domain.operation.dto;

import java.time.Instant;
import java.util.Set;

import org.sscc.ssccopsserver.domain.operation.entity.ApprovalStatus;
import org.sscc.ssccopsserver.domain.operation.entity.WorkStatus;

/*
 * 해석이 끝난 목록 조회 조건 (OPS-008). Repository는 이 값만 보고 쿼리를 만든다.
 *
 * 요청 DTO(SubWorkSearchCondition)를 그대로 넘기지 않는 이유는 그쪽이 아직 문자열이기
 * 때문이다. 기준 코드 위반·커서 해독 실패는 요청을 받는 자리에서 한 번에 걸러야 하고,
 * 그 판정이 Repository까지 흘러 들어가면 조회 코드가 400을 던지게 된다 (LY-02).
 *
 * overdueBefore를 필드로 들고 있는 것은 지연·마감임박 판정이 기준 시각을 필요로 하기
 * 때문이다. '지금'이 아니라 서비스 표준 시간대의 오늘 0시이며(DeadlinePolicy.overdueBefore,
 * #121), 지연 칩과 마감임박 칩이 같은 값을 경계로 써야 두 칩이 겹치지도 사이에 건을
 * 빠뜨리지도 않는다. 한 번만 읽어 목록·건수 쿼리가 모두 같은 경계를 쓰게 한다 — 쿼리마다
 * 다시 계산하면 경계에 걸친 건이 목록에는 있고 건수에는 없는 상태가 생긴다.
 */
public record SubWorkSearchQuery(
        WorkStatus workStatus,
        Set<ApprovalStatus> approvalStatuses,
        boolean overdueOnly,
        Instant dueBefore,
        Instant overdueBefore,
        int size,
        SubWorkSortOrder sort,
        SubWorkCursor cursor) {

    public boolean hasWorkStatusFilter() {
        return workStatus != null;
    }

    // 빈 집합은 '필터 없음'이다. 빈 컬렉션을 그대로 IN에 넘기면 DB에 따라 문법 오류가 난다
    public boolean hasApprovalStatusFilter() {
        return !approvalStatuses.isEmpty();
    }

    public boolean hasDueBeforeFilter() {
        return dueBefore != null;
    }

    public boolean hasCursor() {
        return cursor != null;
    }

    // hasNext 판정용으로 한 건 더 읽는다. 마지막 페이지인지 알려면 다음 건의 존재만 보면 된다
    public int fetchSize() {
        return size + 1;
    }
}
