package org.sscc.ssccopsserver.domain.academicprogram.dto;

import org.sscc.ssccopsserver.domain.academicprogram.entity.SessionStatus;

/*
 * 해석이 끝난 회차 목록 조회 조건(#135). Repository는 이 값만 보고 쿼리를 만든다 —
 * AcademicProgramSearchQuery와 같은 경계(LY-02)다.
 *
 * academicProgramId는 필터가 아니라 범위다. 화면이 생략할 수 없고 생략하면 다른 활동의 회차가
 * 섞이므로, 질의가 애초에 그 활동 것만 읽는다(커리큘럼 조회 #134와 같은 태도).
 */
public record SessionSearchQuery(
        Long academicProgramId,
        SessionStatus status,
        int size,
        SessionSortOrder sort,
        SessionCursor cursor) {

    public boolean hasStatusFilter() {
        return status != null;
    }

    public boolean hasCursor() {
        return cursor != null;
    }

    // hasNext 판정용으로 한 건 더 읽는다
    public int fetchSize() {
        return size + 1;
    }
}
