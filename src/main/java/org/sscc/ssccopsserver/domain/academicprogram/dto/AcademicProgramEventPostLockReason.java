package org.sscc.ssccopsserver.domain.academicprogram.dto;

/*
 * 행사 게시물이 잠긴 이유 (ssccops#618 · ADR-0069 참고). 조회 응답의 lockReason이며 열려 있으면 null이다.
 *
 * 화면이 고를 안내가 셋이라 셋이다 — 접수 종료는 «학술국장에게 부탁하세요», 수료는 «재시작되면»,
 * 폐지는 «복원되면». 겹치면 프로그램 상태가 앞선다(수료가 접수 중인 폼을 같은 트랜잭션에서 마감하므로
 * 수료된 프로그램은 대개 접수도 끝나 있다 — 이때 «접수가 끝났다»는 덜 정확한 이유다). 쓰기 경로의
 * 거절 순서(AcademicProgramWritePolicy의 수료 · 폐지 409가 접수 409보다 먼저)와 같다.
 */
public enum AcademicProgramEventPostLockReason {
    RECRUITMENT_CLOSED,
    PROGRAM_COMPLETED,
    PROGRAM_DISCONTINUED
}
