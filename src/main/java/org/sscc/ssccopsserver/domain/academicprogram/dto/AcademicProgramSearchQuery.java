package org.sscc.ssccopsserver.domain.academicprogram.dto;

import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramStatus;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/*
 * 해석이 끝난 목록 조회 조건(#131). Repository는 이 값만 보고 쿼리를 만든다 — work 도메인의
 * WorkSearchQuery와 같은 경계(LY-02)다.
 *
 * mine은 Boolean이 아니라 MemberEntity다. true일 때만 "나"를 식별할 수 있어야 필터가 성립하고,
 * Repository가 다시 인증 주체를 알아낼 방법이 없으므로 여기서 이미 회원으로 해석해 넘긴다.
 */
public record AcademicProgramSearchQuery(
        AcademicProgramStatus status,
        String typeCd,
        String keyword,
        MemberEntity mine,
        int size,
        AcademicProgramSortOrder sort,
        AcademicProgramCursor cursor) {

    public boolean hasStatusFilter() {
        return status != null;
    }

    public boolean hasTypeFilter() {
        return typeCd != null;
    }

    public boolean hasKeywordFilter() {
        return keyword != null;
    }

    public boolean hasMineFilter() {
        return mine != null;
    }

    public boolean hasCursor() {
        return cursor != null;
    }

    // hasNext 판정용으로 한 건 더 읽는다
    public int fetchSize() {
        return size + 1;
    }
}
