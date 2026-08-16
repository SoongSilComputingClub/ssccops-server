package org.sscc.ssccopsserver.domain.member.service;

import java.util.List;
import java.util.Set;

import org.sscc.ssccopsserver.domain.member.code.MemberHistorySource;
import org.sscc.ssccopsserver.domain.member.dto.MemberChangeHistoryResponse;

/*
 * 회원 변경 이력 통합 조회 (#82).
 *
 * MemberService에 얹지 않고 별도 빈으로 두는 것은 다루는 것이 회원이 아니라 **세 이력
 * 테이블**이기 때문이다. MemberServiceImpl은 이미 가입·수정·검색·부트스트랩을 안고 있고,
 * 이력 조회는 그중 어느 것과도 트랜잭션이나 규칙을 나눠 쓰지 않는다.
 */
public interface MemberHistoryService {

    /**
     * 회원의 변경 이력을 발생 시각 역순으로 전부 내린다.
     *
     * @param sources 읽을 출처. 비어 있을 수 없으며 '전부'는 세 값을 모두 담아 표현한다 — 빈 집합을 '전부'로 읽으면 필터를 잘못 만든 요청이 조용히
     *     전량 조회가 된다.
     */
    List<MemberChangeHistoryResponse> getHistories(Long memberId, Set<MemberHistorySource> sources);
}
