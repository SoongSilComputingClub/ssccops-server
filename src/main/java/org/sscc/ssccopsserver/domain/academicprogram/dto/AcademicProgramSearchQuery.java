package org.sscc.ssccopsserver.domain.academicprogram.dto;

import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramStatus;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/*
 * 해석이 끝난 목록 조회 조건(#131). Repository는 이 값만 보고 쿼리를 만든다 — work 도메인의
 * WorkSearchQuery와 같은 경계(LY-02)다.
 *
 * mine은 Boolean이 아니라 MemberEntity다. 필터가 걸렸을 때만 "나"를 식별할 수 있어야 필터가
 * 성립하고, Repository가 다시 인증 주체를 알아낼 방법이 없으므로 여기서 이미 회원으로 해석해
 * 넘긴다.
 *
 * mineRole은 그 회원을 **어느 역할로 볼 것인가**다(#215 · 스터디장 · 제출자 · 둘 다). 두 값은
 * 언제나 함께 있거나 함께 없다 — 회원 없이 역할만 오면 무엇과 비교할지가 없고, 역할 없이
 * 회원만 오면 Repository가 다시 기본 역할을 정해야 해서 "기본이 무엇인가"가 두 곳에 놓인다.
 * 그 불변식을 생성자에서 깬다.
 */
public record AcademicProgramSearchQuery(
        AcademicProgramStatus status,
        String typeCd,
        String keyword,
        MemberEntity mine,
        AcademicProgramMineRole mineRole,
        int size,
        AcademicProgramSortOrder sort,
        AcademicProgramCursor cursor) {

    public AcademicProgramSearchQuery {
        if ((mine == null) != (mineRole == null)) {
            throw new IllegalArgumentException("mine과 mineRole은 함께 있거나 함께 없어야 합니다.");
        }
    }

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
