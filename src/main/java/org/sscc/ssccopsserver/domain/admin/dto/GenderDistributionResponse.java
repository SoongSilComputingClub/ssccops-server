package org.sscc.ssccopsserver.domain.admin.dto;

import lombok.Builder;

@Builder
public record GenderDistributionResponse(
        long maleCount, long femaleCount, double malePercentage, double femalePercentage) {}
