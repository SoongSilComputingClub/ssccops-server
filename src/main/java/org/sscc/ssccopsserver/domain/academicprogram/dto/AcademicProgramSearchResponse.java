package org.sscc.ssccopsserver.domain.academicprogram.dto;

import java.util.List;

import org.sscc.ssccopsserver.global.apipayload.PageResponse;

/*
 * 목록 조회(#131) 결과. 카드 목록과 페이지 봉투를 함께 들고 나온다 — work 도메인의
 * WorkSearchResponse와 같은 이유로 Service가 ApiResponse를 직접 만들지 않는다(LY-03).
 */
public record AcademicProgramSearchResponse(
        List<AcademicProgramSummaryResponse> academicPrograms, PageResponse page) {}
