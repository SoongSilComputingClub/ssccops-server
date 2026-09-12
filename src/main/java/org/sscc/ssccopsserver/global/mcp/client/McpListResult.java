package org.sscc.ssccopsserver.global.mcp.client;

import java.util.List;

/**
 * 목록 도구의 출력 (#385). 항목 타입은 REST 응답 record 그대로이고, 이 record는 `ApiResponse.page` 봉투를 도구 쪽으로 옮긴 것뿐이다 —
 * 커서를 {@link McpRestClient#MAX_PAGES}페이지까지만 따라가므로 «더 있는가»와 «어디서 이어 부르는가»를 모델에게 알려야 한다.
 *
 * @param items 합친 항목
 * @param pagesFetched 실제로 읽은 페이지 수
 * @param hasMore 상한에 걸려 남은 페이지가 있는가
 * @param nextCursor 이어 부를 때 조건의 `cursor`에 넣을 값 (hasMore가 아니면 null)
 * @param totalCount 조건에 맞는 전체 건수 (서버가 준 값)
 */
public record McpListResult<T>(
        List<T> items, int pagesFetched, boolean hasMore, String nextCursor, long totalCount) {}
