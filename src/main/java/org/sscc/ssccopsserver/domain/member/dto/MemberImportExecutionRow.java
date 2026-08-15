package org.sscc.ssccopsserver.domain.member.dto;

import org.sscc.ssccopsserver.domain.member.code.MemberImportExecutionStatus;

/*
 * 이관 실행의 행별 결과 한 줄 (#85).
 *
 * - rowNo: 검증(#84)과 같은 값 — **원본 CSV의 물리적 줄 번호이며 헤더를 1행으로 센다.**
 *   두 응답의 rowNo가 같아야 운영자가 검증 화면에서 본 줄과 결과 화면의 줄을 맞출 수 있다
 * - target: 검증이 만든 표시 문자열("오세현 202112044"). 여기서 다시 만들지 않는다
 * - mbrId: CREATED일 때만 채워진다. 화면이 방금 들어간 회원으로 바로 이동할 수 있게 하는 값이다
 * - reason: SKIPPED·FAILED일 때만 채워진다
 *
 * mbrId와 reason은 서로 배타적이다. 한 필드에 합치지 않는 것은 타입이 다르고, 화면이 하나는
 * 링크로 다른 하나는 문장으로 그리기 때문이다.
 */
public record MemberImportExecutionRow(
        long rowNo,
        String target,
        MemberImportExecutionStatus status,
        Long mbrId,
        String reason) {

    public static MemberImportExecutionRow created(long rowNo, String target, Long memberId) {
        return new MemberImportExecutionRow(
                rowNo, target, MemberImportExecutionStatus.CREATED, memberId, null);
    }

    public static MemberImportExecutionRow skipped(long rowNo, String target, String reason) {
        return new MemberImportExecutionRow(
                rowNo, target, MemberImportExecutionStatus.SKIPPED, null, reason);
    }

    public static MemberImportExecutionRow failed(long rowNo, String target, String reason) {
        return new MemberImportExecutionRow(
                rowNo, target, MemberImportExecutionStatus.FAILED, null, reason);
    }
}
