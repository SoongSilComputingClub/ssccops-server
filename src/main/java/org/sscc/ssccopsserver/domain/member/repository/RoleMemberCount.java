package org.sscc.ssccopsserver.domain.member.repository;

/*
 * 역할별 현재 보유 회원 수 집계 결과 (#79 역할 목록).
 *
 * 목록이 역할마다 "3명 사용"을 보여주는데 역할 수만큼 count 쿼리를 날리면 그대로 N+1이다
 * (DB-13). 회원 전체가 아니라 숫자만 있으면 되므로 한 번의 집계 쿼리로 받는다 —
 * FormResponseCount·SubWorkChecklistProgress와 같은 방식이다.
 *
 * '현재 보유'의 기준은 role_bgng_ymd <= 오늘 <= role_end_ymd(종료일 NULL이면 무기한)이며,
 * AuthorityPolicy가 유효 역할을 고르는 기준과 같다. 오늘 날짜는 주입된 Clock에서 오므로
 * 질의 파라미터로 받는다 — LocalDate.now()를 질의 안에 박으면 테스트에서 고정할 수 없다.
 *
 * 재임자가 한 명도 없는 역할은 GROUP BY 결과에 아예 나오지 않는다. 그것을 0명으로 볼지는
 * 여기가 아니라 호출부가 정한다.
 */
public interface RoleMemberCount {

    Long getRoleId();

    long getMemberCount();
}
