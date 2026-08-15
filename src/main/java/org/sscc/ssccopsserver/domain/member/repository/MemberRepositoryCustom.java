package org.sscc.ssccopsserver.domain.member.repository;

import java.util.List;

import org.sscc.ssccopsserver.domain.member.dto.MemberSearchQuery;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/*
 * 회원 목록 조회(#76)의 동적 조건 부분. 검색어·등급·상태 필터와 커서가 서로 독립적으로
 * 붙었다 떨어지고 정렬 키도 넷이라, 메서드 이름으로 조건을 표현하는 파생 쿼리로는
 * 조합 수만큼 메서드가 늘어난다 (WorkRepositoryCustom과 같은 판단).
 *
 * Spring Data는 <Repository이름>Impl을 같은 패키지에서 찾아 MemberRepository에 합쳐 준다.
 */
public interface MemberRepositoryCustom {

    /*
     * 조건에 맞는 회원을 정렬 순서대로 읽는다. 다음 페이지가 있는지 알기 위해 size보다
     * 한 건 더 읽어 돌려주므로, 잘라내는 것은 호출부의 몫이다.
     *
     * 등급·상태는 지연 로딩이라 목록 조립 단계에서 회원 수만큼 쿼리가 더 나간다 —
     * 같은 SELECT의 조인으로 함께 끌어온다 (DB-13).
     */
    List<MemberEntity> search(MemberSearchQuery query);

    // 같은 조건의 총 건수. 커서·정렬과는 무관하다
    long countMatching(MemberSearchQuery query);
}
