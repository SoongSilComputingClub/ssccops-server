package org.sscc.ssccopsserver.domain.member.dto;

import java.util.List;

/*
 * 해석이 끝난 회원 목록 조회 조건 (#76). Repository는 이 값만 보고 쿼리를 만든다.
 *
 * 요청 DTO(MemberSearchCondition)를 그대로 넘기지 않는 것은 그쪽이 아직 문자열이기 때문이다.
 * 기준 코드 위반·커서 해독 실패는 요청을 받는 자리에서 한 번에 걸러야 하고, 그 판정이
 * Repository까지 흘러 들어가면 조회 코드가 400을 던지게 된다 (LY-02 · WorkSearchQuery와 같은 판단).
 *
 * keyword는 정리만 된 원문이다 — like 패턴으로 감싸고 와일드카드를 이스케이프하는 일은
 * 질의 표기에 속하므로 Repository가 한다.
 */
public record MemberSearchQuery(
        String keyword,
        List<String> gradeCodes,
        List<String> statusCodes,
        int size,
        MemberSortOrder sort,
        MemberCursor cursor) {

    public boolean hasKeyword() {
        return keyword != null;
    }

    public boolean hasGradeFilter() {
        return !gradeCodes.isEmpty();
    }

    public boolean hasStatusFilter() {
        return !statusCodes.isEmpty();
    }

    public boolean hasCursor() {
        return cursor != null;
    }

    // hasNext 판정용으로 한 건 더 읽는다. 마지막 페이지인지 알려면 다음 건의 존재만 보면 된다
    public int fetchSize() {
        return size + 1;
    }
}
