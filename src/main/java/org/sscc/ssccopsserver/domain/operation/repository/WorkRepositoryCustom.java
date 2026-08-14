package org.sscc.ssccopsserver.domain.operation.repository;

import java.util.List;

import org.sscc.ssccopsserver.domain.operation.dto.WorkSearchQuery;
import org.sscc.ssccopsserver.domain.operation.entity.WorkEntity;

/*
 * 상위 업무 목록 조회(OPS-020)의 동적 조건 부분. 필터 둘과 커서가 서로 독립적으로 붙었다
 * 떨어지므로 메서드 이름으로 조건을 표현하는 파생 쿼리로는 조합 수만큼 메서드가 늘어난다.
 *
 * Spring Data는 <Repository이름>Impl을 같은 패키지에서 찾아 WorkRepository에 합쳐 준다.
 */
public interface WorkRepositoryCustom {

    /*
     * 조건에 맞는 업무를 정렬 순서대로 읽는다. 다음 페이지가 있는지 알기 위해 size보다
     * 한 건 더 읽어 돌려주므로, 잘라내는 것은 호출부의 몫이다.
     */
    List<WorkEntity> search(WorkSearchQuery query);

    // 같은 조건의 총 건수. 커서·정렬과는 무관하다
    long countMatching(WorkSearchQuery query);
}
