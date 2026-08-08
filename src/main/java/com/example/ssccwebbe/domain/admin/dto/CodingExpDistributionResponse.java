package com.example.ssccwebbe.domain.admin.dto;

import java.util.List;

import lombok.Builder;

@Builder
public record CodingExpDistributionResponse(
        long totalCount, List<ExpLevelDistribution> distributions) {
    @Builder
    public record ExpLevelDistribution(
            String level, // A, B, C, D, E
            String description,
            long count,
            double percentage) {}
}
