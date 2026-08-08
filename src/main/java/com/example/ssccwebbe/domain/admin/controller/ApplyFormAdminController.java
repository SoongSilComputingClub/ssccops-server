package com.example.ssccwebbe.domain.admin.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.ssccwebbe.domain.admin.dto.CodingExpDistributionResponse;
import com.example.ssccwebbe.domain.admin.dto.GenderDistributionResponse;
import com.example.ssccwebbe.domain.admin.service.ApplyFormAdminService;
import com.example.ssccwebbe.domain.applyform.dto.ApplyFormReadResponse;
import com.example.ssccwebbe.global.apipayload.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

@Tag(name = "관리자용 지원서 API", description = "지원서 통계 및 전체 조회를 위한 관리자 전용 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/apply-forms")
public class ApplyFormAdminController {

    private final ApplyFormAdminService applyFormAdminService;

    @Operation(summary = "성별 분포 조회", description = "전체 지원자의 남/여 비율을 조회합니다.")
    @GetMapping("/gender-distribution")
    public ApiResponse<GenderDistributionResponse> getGenderDistribution() {
        return ApiResponse.success(applyFormAdminService.getGenderDistribution());
    }

    @Operation(summary = "코딩 경험 분포 조회", description = "전체 지원자의 코딩 경험 단계별 분포를 조회합니다.")
    @GetMapping("/coding-exp-distribution")
    public ApiResponse<CodingExpDistributionResponse> getCodingExpDistribution() {
        return ApiResponse.success(applyFormAdminService.getCodingExpDistribution());
    }

    @Operation(summary = "전체 지원서 조회", description = "삭제되지 않은 모든 지원서 정보를 상세하게 조회합니다.")
    @GetMapping
    public ApiResponse<List<ApplyFormReadResponse>> getAllApplications() {
        return ApiResponse.success(applyFormAdminService.getAllApplications());
    }
}
