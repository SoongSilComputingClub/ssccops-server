package org.sscc.ssccopsserver.domain.operation.repository;

import java.time.Instant;

/*
 * 하위 업무별 검토요청 집계 (OPS-017 · #47).
 *
 * 승인함 카드가 쓰는 두 값을 한 번의 집계로 함께 얻는다.
 *  - reviewCount: 검토(REVIEW)에 들어간 횟수 = 지금이 몇 번째 승인 회차인지. 정족수 집계가
 *    이번 회차의 표만 세기 위해 필요하다.
 *  - lastRequestedAt: 마지막 검토요청 시각 = 카드의 '요청 …' 일시. 등록 일시가 아니다.
 *
 * 카드마다 이력을 조회하면 그대로 N+1이라 목록 전체를 한 번에 집계한다 (DB-13).
 * 아직 검토요청을 한 번도 하지 않은 하위 업무는 결과에 나오지 않는다.
 */
public interface SubWorkReviewRequest {

    Long getSubWorkId();

    long getReviewCount();

    Instant getLastRequestedAt();
}
