package org.sscc.ssccopsserver.domain.operation.dto;

import java.util.List;

/*
 * 완료 체크리스트 진행 요약. 상세 화면의 '0/4 완료' 표기가 이 값이다.
 * 목록을 함께 내려주므로 클라이언트가 셀 수도 있지만, 세는 규칙을 서버에 두어
 * 다른 화면·목록 API가 같은 값을 다르게 계산하는 것을 막는다.
 */
public record SubWorkChecklistSummaryResponse(long completedCount, long totalCount) {

    public static SubWorkChecklistSummaryResponse from(
            List<SubWorkChecklistItemResponse> checklist) {
        long completedCount =
                checklist.stream().filter(SubWorkChecklistItemResponse::isCompleted).count();
        return new SubWorkChecklistSummaryResponse(completedCount, checklist.size());
    }
}
