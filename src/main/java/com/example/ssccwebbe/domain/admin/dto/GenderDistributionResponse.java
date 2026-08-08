package com.example.ssccwebbe.domain.admin.dto;

import lombok.Builder;

@Builder
public record GenderDistributionResponse(
        long maleCount, long femaleCount, double malePercentage, double femalePercentage) {}
