package org.sscc.ssccopsserver.domain.academicprogram.dto;

import java.util.Set;

import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramStatus;
import org.sscc.ssccopsserver.domain.academicprogram.entity.SessionStatus;

/*
 * 해석이 끝난 회차 목록 조회 조건(#135·#136). Repository는 이 값만 보고 쿼리를 만든다 —
 * AcademicProgramSearchQuery와 같은 경계(LY-02)다.
 *
 * 활동 하나의 목록(#135)과 활동 횡단 목록(#136)이 같은 record를 쓴다. 조건 조립과 커서 비교식이
 * 완전히 같고 다른 것은 무엇을 함께 읽어 오느냐(Repository의 SELECT 절)뿐이라, 둘로 나누면
 * 커서·정렬 규칙이 두 벌이 되어 같은 sort 값이 두 API에서 다른 순서를 낼 여지가 생긴다.
 *
 * academicProgramId는 그래서 #135에서는 생략할 수 없는 범위이고 #136에서는 생략 가능한
 * 필터다 — NULL이면 활동 경계 없이 전부 읽는다. 활동 하나짜리 목록이 이 값을 비운 채 호출되는
 * 일은 없다(경로 변수에서 온다).
 *
 * programStatuses는 승인 대기 목록(#597)만 쓴다 — 종료된 활동의 회차를 빼는 자리다
 * (SessionReviewCondition). NULL이면 활동 상태를 보지 않는다. 활동 횡단 질의(p 별칭이 있는
 * SELECT)에서만 붙는다는 점은 keyword와 같다.
 */
public record SessionSearchQuery(
        Long academicProgramId,
        SessionStatus status,
        String keyword,
        Set<AcademicProgramStatus> programStatuses,
        int size,
        SessionSortOrder sort,
        SessionCursor cursor) {

    public boolean hasAcademicProgramFilter() {
        return academicProgramId != null;
    }

    public boolean hasStatusFilter() {
        return status != null;
    }

    /** 활동명·회차 주제 부분일치(#136). 활동 하나짜리 목록(#135)은 이 필터를 받지 않는다 */
    public boolean hasKeywordFilter() {
        return keyword != null;
    }

    /** 활동 상태로 좁히는가(#597). 승인 대기 목록만 쓴다 */
    public boolean hasProgramStatusFilter() {
        return programStatuses != null;
    }

    public boolean hasCursor() {
        return cursor != null;
    }

    // hasNext 판정용으로 한 건 더 읽는다
    public int fetchSize() {
        return size + 1;
    }
}
