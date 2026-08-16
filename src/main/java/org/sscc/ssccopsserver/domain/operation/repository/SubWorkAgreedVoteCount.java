package org.sscc.ssccopsserver.domain.operation.repository;

/*
 * 하위 업무·회차별 찬성 수 (OPS-017 · #47). 승인함의 정족수 진행바가 쓰는 값이며,
 * 카드마다 세면 N+1이라 목록 전체를 한 번에 집계한다 (DB-13).
 *
 * 회차를 함께 내리는 것은 이번 회차의 표만 골라내기 위해서다 — 반려 후 다시 올라온 건은
 * 이전 회차의 찬성이 그대로 남아 있고, 그것까지 세면 초기화가 무의미해진다.
 * 찬성이 한 표도 없는 하위 업무는 결과에 나오지 않으므로 호출부가 0으로 채운다.
 */
public interface SubWorkAgreedVoteCount {

    Long getSubWorkId();

    int getApprovalSequence();

    long getAgreedCount();
}
