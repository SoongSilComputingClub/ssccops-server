package org.sscc.ssccopsserver.domain.member.dto;

import java.util.List;

import org.sscc.ssccopsserver.domain.member.code.MemberBulkChangeStatus;

/*
 * 일괄 변경의 회원별 결과 한 줄 (#338).
 *
 * ── 왜 회원 상세를 싣지 않는가 ─────────────────────────────────────
 * 한 명짜리 응답(MemberGradeChangeResponse)은 변경 후 MemberDetailResponse를 통째로 싣는다 —
 * 화면이 상세를 다시 조회하지 않게 하려는 것이다. 여기서 같은 것을 하면 최대 100명분의 상세가
 * (각자 역할 목록과 최근 이력 3건을 달고) 한 응답에 들어간다. 일괄 변경 뒤 화면이 돌아가는 곳은
 * **상세가 아니라 목록**이고, 목록은 어차피 다시 조회된다. 그래서 결과 표를 그리는 데 필요한
 * 것만 남긴다 — 누가(memberId·name), 어떻게 됐고(status), 왜(code·reason).
 *
 * - memberId: 요청에 실려 온 값 그대로다. FAILED여도 채워진다 — 실패 줄이야말로 어느 회원인지
 *   가리켜야 하고, 없는 회원 id라도 화면은 그 숫자를 그대로 보여 줘야 운영자가 목록에서 찾는다
 * - name: 회원명. **없는 회원이면 null이다** — 이름을 알아낼 곳이 없다. 빈 문자열로 채우지 않는
 *   것은 화면이 "(이름 없음)"과 "이름이 빈 회원"을 구별해야 하기 때문이다
 * - code: SKIPPED·FAILED일 때의 오류 코드 문자열(NO_CHANGE·NOT_FOUND·INVALID_CODE_VALUE …).
 *   한 명짜리 API가 오류 응답의 code로 내리던 바로 그 값이며, 프론트가 이미 그 문자열로 분기하고
 *   있으므로 일괄에서도 같은 값을 준다. CHANGED면 null이다
 * - reason: 사람이 읽는 사유. code와 함께 싣는 것은 CSV 이관 결과 행과 같은 판단이다 —
 *   화면이 문구를 다시 적지 않아도 되고, 서버가 문구를 고치면 화면도 함께 바뀐다
 * - warnings: 탈퇴·제명으로 바꾼 회원에게 남아 있는 역할·담당 업무. 한 명짜리 응답이 내리던 값을
 *   **회원마다 그 줄에** 싣는다
 *
 * ── warnings를 요약으로 합치지 않는 이유 ───────────────────────────
 * "역할 7건이 남았습니다"를 한 줄로 합치면 그 7건이 누구 것인지가 사라진다. 경고의 쓸모는 사람이
 * 가서 정리하는 것이고, 그러려면 회원별로 붙어 있어야 한다 (MemberChangeWarningResponse 주석 —
 * 경고는 자동 정리를 하지 않기로 한 자리에 남긴 표시다). 전체 건수가 필요하면 화면이 더하면 된다.
 *
 * CHANGED가 아닌 줄의 warnings는 언제나 비어 있다. 바뀌지 않은 회원에게 "탈퇴 후에도 역할이
 * 남습니다"는 거짓이다 (이관 검증이 ERROR 행의 warnings를 비우는 것과 같은 판단, #109).
 */
public record MemberBulkChangeRow(
        Long memberId,
        String name,
        MemberBulkChangeStatus status,
        String code,
        String reason,
        List<MemberChangeWarningResponse> warnings) {

    public static MemberBulkChangeRow changed(
            Long memberId, String name, List<MemberChangeWarningResponse> warnings) {
        return new MemberBulkChangeRow(
                memberId, name, MemberBulkChangeStatus.CHANGED, null, null, warnings);
    }

    public static MemberBulkChangeRow skipped(
            Long memberId, String name, String code, String reason) {
        return new MemberBulkChangeRow(
                memberId, name, MemberBulkChangeStatus.SKIPPED, code, reason, List.of());
    }

    public static MemberBulkChangeRow failed(
            Long memberId, String name, String code, String reason) {
        return new MemberBulkChangeRow(
                memberId, name, MemberBulkChangeStatus.FAILED, code, reason, List.of());
    }
}
