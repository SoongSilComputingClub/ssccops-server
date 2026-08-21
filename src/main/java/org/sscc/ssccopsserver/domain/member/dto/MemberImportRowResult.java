package org.sscc.ssccopsserver.domain.member.dto;

import java.util.List;

import org.sscc.ssccopsserver.domain.member.code.MemberImportRowStatus;

/*
 * 행별 검증 결과 (#84).
 *
 * - rowNo: **원본 CSV의 물리적 줄 번호이며 헤더를 1행으로 센다.** 헤더가 첫 줄이므로 첫 데이터
 *   행은 2다. 레코드 순번이 아닌 것은 운영자가 파일을 열어 그 줄을 찾기 때문이며, 줄바꿈을 포함한
 *   필드가 있는 파일에서는 두 값이 갈린다(레코드 3번이 7행일 수 있다).
 * - target: 목록에서 사람을 알아보기 위한 표시 문자열("오세현 202112044"). 이름이 없으면
 *   "(회원명 없음)"이다 — 빈 칸으로 두면 오류 목록에서 어느 줄인지 rowNo만 남는다
 * - reasons: 이관을 막는 사유. status가 OK면 비어 있다
 * - warnings: **이관을 막지 않는** 지적. 지금은 연락처 누락 하나다(ssccops#78 A안 — 연락처가 빈
 *   회원은 나중에 스스로 계정을 연결할 수 없다). 오류와 같은 목록에 담지 않는 것은 화면이
 *   "고쳐야 진행되는 것"과 "고치면 좋은 것"을 다르게 그려야 하기 때문이다.
 *   **status가 OK가 아니면 비어 있다** — 그 행은 이관되지 않으므로 "이관은 되지만 나중에 불편하다"가
 *   그 행에 대해 거짓이다(#109). 사유를 고쳐 다시 검증하면 그때 경고가 나타난다
 */
public record MemberImportRowResult(
        long rowNo,
        String target,
        MemberImportRowStatus status,
        List<MemberImportRowIssue> reasons,
        List<MemberImportRowIssue> warnings) {

    /** 행별 사유 한 건. field는 MemberImportField.key()이며, 행 전체에 걸리는 사유는 null이다 */
    public record MemberImportRowIssue(String field, String message) {}
}
