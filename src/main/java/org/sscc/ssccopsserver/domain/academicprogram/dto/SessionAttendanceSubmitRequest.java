package org.sscc.ssccopsserver.domain.academicprogram.dto;

import jakarta.validation.constraints.NotNull;

/*
 * 회차 기록 제출·재제출(#135)에 동봉되는 출석 한 줄. 화면이 진행 내용과 출석 체크를 한 화면·단일
 * 제출 버튼으로 받으므로 별도 요청으로 나누지 않는다(학술관리_API설계.md §3.4).
 *
 * 대상은 회원(mbrId)이 아니라 확정 팀원(eventPtcpId)이다 — 출석은 "이 활동의 팀원으로서" 하는
 * 것이라 대기자·취소자에게는 성립하지 않는다(설계 결정 #3). 실제로 그 활동의 확정 팀원인지는
 * 서비스가 검증하고 아니면 400 INVALID_ATTENDANCE_TARGET이다.
 *
 * presentYn이 Boolean(원시형이 아니라)인 것은 "빠뜨린 것"과 "결석"을 구별하기 위해서다 —
 * boolean이면 필드를 빠뜨린 요청이 조용히 결석으로 저장된다.
 */
public record SessionAttendanceSubmitRequest(
        @NotNull(message = "eventPtcpId는 필수입니다.") Long eventPtcpId,
        @NotNull(message = "presentYn은 필수입니다.") Boolean presentYn) {}
