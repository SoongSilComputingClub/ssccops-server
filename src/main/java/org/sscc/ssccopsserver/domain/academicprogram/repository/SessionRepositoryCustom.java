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

    /*
     * 활동을 가로지르는 회차 목록(#136). 조건·정렬·커서는 search와 완전히 같고 다른 것은 함께
     * 읽어 오는 것뿐이다 — 이 목록은 줄마다 활동명·유형을 보여주므로 활동·행사·유형까지 한 번에
     * 끌어온다. search로 합치고 fetch join만 늘리면 활동 상세 안의 목록도 쓰지 않는 조인을
     * 매번 지고 간다.
     */
    List<SessionEntity> searchCross(SessionSearchQuery query);

    // 같은 조건의 총 건수. 커서·정렬과는 무관하다
    long countMatching(SessionSearchQuery query);

    // 활동 전체의 회차 수. 필터와 무관한 분모다(page.overallCount)
    long countByAcademicProgramId(Long academicProgramId);
}
