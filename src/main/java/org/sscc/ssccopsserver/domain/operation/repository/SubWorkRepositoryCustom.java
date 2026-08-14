package org.sscc.ssccopsserver.domain.operation.repository;

import java.util.List;

import org.sscc.ssccopsserver.domain.operation.dto.SubWorkSearchQuery;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkEntity;

/*
 * 목록 조회(OPS-008)의 동적 조건 부분. 필터 네 개가 서로 독립적으로 붙었다 떨어지므로
 * 메서드 이름으로 조건을 표현하는 파생 쿼리로는 조합 수만큼 메서드가 늘어난다.
 *
 * Spring Data는 <Repository이름>Impl을 같은 패키지에서 찾아 SubWorkRepository에 합쳐 준다.
 */
public interface SubWorkRepositoryCustom {

    /*
     * 조건에 맞는 하위 업무를 정렬 순서대로 읽는다. 다음 페이지가 있는지 알기 위해
     * size보다 한 건 더 읽어 돌려주므로, 잘라내는 것은 호출부의 몫이다.
     */
    List<SubWorkEntity> search(SubWorkSearchQuery query);

    // 같은 조건의 총 건수. 커서·정렬과는 무관하다
    long countMatching(SubWorkSearchQuery query);
}
