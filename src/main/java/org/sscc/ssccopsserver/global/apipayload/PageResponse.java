package org.sscc.ssccopsserver.global.apipayload;

/*
 * 목록 응답의 페이지 봉투 (AP-11 — 목록은 {"data":[], "page":{}}).
 *
 * 페이징은 offset이 아니라 커서 기반이다 (AP-13). 목록 조회와 데이터 추가가 겹칠 때
 * offset은 항목이 중복되거나 누락되지만 커서는 그렇지 않다. 그래서 page·totalPages 같은
 * 페이지 번호 개념을 두지 않고 nextCursor·hasNext만 내린다.
 *
 * totalCount·overallCount는 커서 페이징에 없는 값인데도 함께 내린다 — 목록 화면이
 * '8건 · 전체 8건'처럼 걸러진 건수와 전체 건수를 나란히 표시하기 때문이다. 두 값 모두
 * count 쿼리를 한 번씩 더 쓰므로, 이 값이 필요 없는 목록 API는 0을 채우지 말고
 * 이 record를 그대로 쓰되 호출부에서 같은 값을 넣지 않도록 주의한다.
 *
 * sort는 서버가 실제로 적용한 정렬이다. 클라이언트가 생략하면 서버 기본값이 그대로 실려
 * 나가므로, 다음 페이지 요청에 이 값을 되돌려주면 정렬이 흔들리지 않는다.
 */
public record PageResponse(
        int size,
        String sort,
        String nextCursor,
        boolean hasNext,
        long totalCount,
        long overallCount) {}
