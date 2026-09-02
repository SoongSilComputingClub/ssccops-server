package org.sscc.ssccopsserver.domain.academicprogram.dto;

import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramApprovalPoint;

/*
 * 해석이 끝난 승인 이력 조회 조건(#139). Repository는 이 값만 보고 쿼리를 만든다 —
 * SessionSearchQuery와 같은 경계(LY-02)다.
 *
 * academicProgramId는 필터가 아니라 **범위**라 생략할 수 없다. 승인 이력은 활동 하나의 것만
 * 열람 자격을 판정할 수 있고(스터디장 본인 또는 학술국장), 활동을 가로지르는 목록을 열면 그
 * 판정 자체가 성립하지 않는다 — 회차 목록(#136)이 활동 횡단을 따로 연 것과 갈리는 자리다.
 */
public record AcademicProgramApprovalSearchQuery(
        Long academicProgramId,
        AcademicProgramApprovalPoint point,
        Long sessionId,
        int size,
        AcademicProgramApprovalCursor cursor) {

    public boolean hasPointFilter() {
        return point != null;
    }

    /*
     * 회차 하나의 처리 이력만 보는 필터(회차 상세의 "검토 기록"). 그 회차가 이 활동의 것인지는
     * 따로 확인하지 않는다 — 질의가 언제나 경로의 활동으로 먼저 좁히므로 남의 회차 번호를
     * 넣으면 빈 목록이고, 그 빈 목록은 무엇도 알려 주지 않는다.
     */
    public boolean hasSessionFilter() {
        return sessionId != null;
    }

    public boolean hasCursor() {
        return cursor != null;
    }

    // hasNext 판정용으로 한 건 더 읽는다
    public int fetchSize() {
        return size + 1;
    }
}
