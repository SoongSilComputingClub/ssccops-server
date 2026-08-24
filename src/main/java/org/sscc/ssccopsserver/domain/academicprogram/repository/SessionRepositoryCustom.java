package org.sscc.ssccopsserver.domain.academicprogram.repository;

import java.util.List;

import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionSearchQuery;
import org.sscc.ssccopsserver.domain.academicprogram.entity.SessionEntity;

/*
 * 회차 목록(#135)의 동적 조건 부분. AcademicProgramRepositoryCustom과 같은 이유로 존재한다 —
 * 상태 필터와 커서 비교식(정렬 키·방향에 따라 네 가지)이 독립적으로 조합되므로 파생 쿼리
 * 메서드 이름으로는 표현이 늘어난다.
 */
public interface SessionRepositoryCustom {

    /*
     * 조건에 맞는 회차를 정렬 순서대로 읽는다. 다음 페이지가 있는지 알기 위해 size보다 한 건
     * 더 읽어 돌려주므로, 잘라내는 것은 호출부의 몫이다.
     */
    List<SessionEntity> search(SessionSearchQuery query);

    // 같은 조건의 총 건수. 커서·정렬과는 무관하다
    long countMatching(SessionSearchQuery query);

    // 활동 전체의 회차 수. 필터와 무관한 분모다(page.overallCount)
    long countByAcademicProgramId(Long academicProgramId);
}
