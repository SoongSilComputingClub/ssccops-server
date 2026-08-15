package org.sscc.ssccopsserver.domain.operation.repository;

/*
 * 하위 업무별 직전 반려 (OPS-017 · #62).
 *
 * 승인함 카드가 반려 사유 한 줄을 그리기 위해 쓰는 값이다. 카드마다 반려를 조회하면 그대로
 * N+1이라(DB-13) 목록 전체를 한 번에 모아 온다 — 상세(OPS-009)가 쓰는 단건 조회
 * (findFirstBySubWorkOrderByRejectedAtDescIdDesc)를 목록에서 돌려쓰면 안 되는 이유다.
 *
 * rejectionId를 함께 싣는 것은 화면에 그리기 위해서가 아니라 동률을 끊기 위해서다 — 같은
 * 트랜잭션에서 두 건이 같은 시각에 기록되면 반려 일시만으로는 어느 건이 최신인지 갈리지 않아
 * 호출부가 식별자가 큰 쪽을 고른다(상세의 정렬 규칙과 같은 기준).
 *
 * 반려된 적이 없는 하위 업무는 결과에 아예 나오지 않는다.
 */
public interface SubWorkLatestRejection {

    Long getSubWorkId();

    Long getRejectionId();

    String getReason();
}
