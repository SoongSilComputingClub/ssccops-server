package org.sscc.ssccopsserver.domain.academicprogram.repository;

import java.util.List;

import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramApprovalSearchQuery;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramApprovalEntity;

/*
 * 승인 이력 목록(#139)의 동적 조건 부분. SessionRepositoryCustom과 같은 이유로 존재한다 —
 * 지점·회차 두 필터와 커서가 서로 독립적으로 조합되므로 파생 쿼리 메서드 이름으로는 표현이
 * 늘어나고, 열거형 필터에 NULL을 넣고 `:point is null`로 분기하면 Hibernate가 타입을 추론하지
 * 못한다(AGENTS.md).
 */
public interface AcademicProgramApprovalRepositoryCustom {

    /*
     * 조건에 맞는 승인 이력을 처리 최신순(식별자 내림차순)으로 읽는다. 다음 페이지가 있는지
     * 알기 위해 size보다 한 건 더 읽어 돌려주므로, 잘라내는 것은 호출부의 몫이다.
     */
    List<AcademicProgramApprovalEntity> search(AcademicProgramApprovalSearchQuery query);

    // 같은 조건의 총 건수. 커서와는 무관하다
    long countMatching(AcademicProgramApprovalSearchQuery query);
}
