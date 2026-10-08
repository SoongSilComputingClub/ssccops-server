package org.sscc.ssccopsserver.domain.member.dto;

/*
 * 회원명부의 한 줄 (#674). 누가 들어가고 직책이 무엇인지는 서비스가 정하고, 이 값을 양식의 어느
 * 칸에 어떤 모양으로 적을지(단대 추정 · 학번 숫자 셀 · 연락처 표기)는 MemberRosterWorkbookWriter가
 * 정한다 — 양식이 바뀌어도 대상 판정은 그대로여야 해서 둘을 나눴다.
 *
 * 값은 mbr에 저장된 그대로다. position이 null이면 빈칸이다(SSCC 표기법에서 대표 역할이 없는 회원).
 */
public record MemberRosterRow(
        String position,
        String name,
        String departmentName,
        String studentNumber,
        Integer academicYear,
        String phoneNumber) {}
