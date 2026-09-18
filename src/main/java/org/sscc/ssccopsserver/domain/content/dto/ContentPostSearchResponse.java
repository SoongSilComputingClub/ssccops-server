package org.sscc.ssccopsserver.domain.content.dto;

import java.util.List;

import org.sscc.ssccopsserver.global.apipayload.PageResponse;

public record ContentPostSearchResponse(
        List<ContentPostSummaryResponse> posts, PageResponse page) {}
