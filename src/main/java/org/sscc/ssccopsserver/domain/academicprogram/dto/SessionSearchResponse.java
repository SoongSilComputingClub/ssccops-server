package org.sscc.ssccopsserver.domain.academicprogram.dto;

import java.util.List;

import org.sscc.ssccopsserver.global.apipayload.PageResponse;

/*
 * 회차 목록(#135) 결과. 목록과 페이지 봉투를 함께 들고 나온다 — AcademicProgramSearchResponse와
 * 같은 이유로 Service가 ApiResponse를 직접 만들지 않는다(LY-03).
 */
public record SessionSearchResponse(List<SessionSummaryResponse> sessions, PageResponse page) {}
