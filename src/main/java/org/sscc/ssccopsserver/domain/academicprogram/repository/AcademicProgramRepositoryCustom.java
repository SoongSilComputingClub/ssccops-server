package org.sscc.ssccopsserver.domain.academicprogram.repository;

import java.util.List;

import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramSearchQuery;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;

/*
 * 목록 조회(#131)의 동적 조건 부분. work 도메인의 WorkRepositoryCustom과 같은 이유로 존재한다 —
 * 필터 셋(typeCd·sttsCd·keyword·mine)과 커서가 독립적으로 조합되므로 파생 쿼리 메서드 이름으로는
 * 표현이 늘어난다.
 */
public interface AcademicProgramRepositoryCustom {

    /*
     * 조건에 맞는 학술 활동을 정렬 순서대로 읽는다. 다음 페이지가 있는지 알기 위해 size보다
     * 한 건 더 읽어 돌려주므로, 잘라내는 것은 호출부의 몫이다.
     */
    List<AcademicProgramEntity> search(AcademicProgramSearchQuery query);

    // 같은 조건의 총 건수. 커서·정렬과는 무관하다
    long countMatching(AcademicProgramSearchQuery query);
}
