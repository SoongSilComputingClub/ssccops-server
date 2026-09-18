package org.sscc.ssccopsserver.domain.content.dto;

import java.util.List;

import org.sscc.ssccopsserver.global.apipayload.PageResponse;

/** 어드민 페이지 목록 + 페이지 봉투. 컨트롤러가 ApiResponse.success(pages, page)로 푼다 */
public record ContentPageSearchResponse(
        List<ContentPageSummaryResponse> pages, PageResponse page) {}
