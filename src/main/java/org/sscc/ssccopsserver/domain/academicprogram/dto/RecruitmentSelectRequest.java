package org.sscc.ssccopsserver.domain.academicprogram.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

/*
 * 선발 확정 요청 (#138 · POST /v1/academic-programs/{academicProgramId}/recruitment/select).
 *
 * 여러 줄을 한 요청으로 받는 것이 요점이다. 화면은 신청자 표에서 체크박스로 고른 뒤 "선발
 * 확정"을 한 번 누르고, 그 조작은 나눌 수 없는 한 건이다 — 줄마다 부르게 하면 중간에 실패한
 * 요청 뒤로 절반만 확정된 명단이 남고, 무엇이 반영됐는지는 화면이 되짚어야 한다.
 *
 * 빈 목록은 400이다. 아무도 고르지 않은 확정은 하려는 일이 없는 요청이라, 통과시키면 응답으로
 * 돌아온 명단을 보고 "선발이 반영됐다"고 읽게 된다.
 */
public record RecruitmentSelectRequest(
        @NotEmpty @Valid List<RecruitmentSelectionRequest> selections) {}
