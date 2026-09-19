package org.sscc.ssccopsserver.domain.content.dto;

import java.util.List;

import org.sscc.ssccopsserver.global.apipayload.PageResponse;

public record PublicContentPostSearchResponse(
        List<PublicContentPostSummaryResponse> posts, PageResponse page) {}
