package com.example.ssccwebbe.domain.applyform.controller;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.ssccwebbe.domain.applyform.dto.ApplyFormCreateOrUpdateRequest;
import com.example.ssccwebbe.domain.applyform.dto.ApplyFormReadResponse;
import com.example.ssccwebbe.domain.applyform.service.ApplyFormService;
import com.example.ssccwebbe.global.apipayload.ApiResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/apply-forms")
public class ApplyFormController {

    private final ApplyFormService applyFormService;

    @GetMapping("/read")
    public ApiResponse<ApplyFormReadResponse> read() {
        return ApiResponse.success(applyFormService.read());
    }

    @PostMapping("/create")
    public ApiResponse<ApplyFormReadResponse> create(
            @Valid @RequestBody ApplyFormCreateOrUpdateRequest req) {
        return ApiResponse.created(applyFormService.create(req));
    }

    @PutMapping("/update")
    public ApiResponse<ApplyFormReadResponse> update(
            @Valid @RequestBody ApplyFormCreateOrUpdateRequest req) {
        return ApiResponse.success(applyFormService.update(req));
    }

    // 소프트 딜리트
    @DeleteMapping("/delete_soft")
    public ApiResponse<Void> deleteSoft() {
        applyFormService.deleteSoft();
        return ApiResponse.successWithNoData();
    }
}
