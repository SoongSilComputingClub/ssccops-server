package org.sscc.ssccopsserver.domain.member.dto;

/*
 * 등급·상태 변경 응답에 함께 실리는 경고 한 줄 (#78).
 *
 * ── 왜 경고인가: 부수 효과를 넣지 않기로 했기 때문이다 ────────────
 * 탈퇴·제명으로 전이할 때 현재 역할을 함께 종료할지, 담당 중인 하위 업무를 어떻게 넘길지는
 * 운영 규칙이 필요한 판단이라 이번 범위 밖이다. 그렇다고 아무 말 없이 상태만 바꾸면 조직을
 * 떠난 회원이 국장 역할과 미완료 업무를 그대로 쥔 채 남는다 — 화면이 "현재 역할 2건 ·
 * 담당 하위 업무 3건이 있습니다"를 띄워 사람이 처리하게 하는 것이 지금의 답이다.
 * 자동 정리는 규칙이 정해진 뒤 별도 이슈로 넣는다.
 *
 * 건수(count)를 문구에 녹이지 않고 따로 싣는 것은 화면이 "역할 정리" 버튼에 배지를 다는 등
 * 값으로 쓰기 때문이다. 프론트는 code로 분기하고 message는 서버 문구를 그대로 쓸 수 있다.
 *
 * **경고는 요청을 막지 않는다.** 오류가 아니라 200 응답에 실리는 사실이다.
 */
public record MemberChangeWarningResponse(String code, String message, long count) {

    /** 조직을 떠난 회원이 아직 쥐고 있는 현재 역할 */
    public static final String CURRENT_ROLES_REMAIN = "CURRENT_ROLES_REMAIN";

    /** 조직을 떠난 회원이 아직 담당 중인(완료되지 않은) 하위 업무 */
    public static final String ASSIGNED_SUB_WORKS_REMAIN = "ASSIGNED_SUB_WORKS_REMAIN";

    public static MemberChangeWarningResponse currentRoles(long count) {
        return new MemberChangeWarningResponse(
                CURRENT_ROLES_REMAIN, "이 회원에게 아직 종료되지 않은 역할이 %d건 있습니다.".formatted(count), count);
    }

    public static MemberChangeWarningResponse assignedSubWorks(long count) {
        return new MemberChangeWarningResponse(
                ASSIGNED_SUB_WORKS_REMAIN, "이 회원이 담당 중인 하위 업무가 %d건 있습니다.".formatted(count), count);
    }
}
