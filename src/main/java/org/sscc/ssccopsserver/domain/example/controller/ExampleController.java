package org.sscc.ssccopsserver.domain.example.controller;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.example.dto.ExampleCreateOrUpdateRequest;
import org.sscc.ssccopsserver.domain.example.dto.ExampleReadResponse;
import org.sscc.ssccopsserver.domain.example.service.ExampleService;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;

import lombok.RequiredArgsConstructor;

/*
 * controller/service/repository/entity/dto/code 6계층 구조를 보여주는 참고용 예시 도메인.
 * 실제 기능이 아니므로, 새 도메인을 추가할 때 이 구조를 복사해 쓰고 example 자체는 지워도 된다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/examples")
public class ExampleController {

    private final ExampleService exampleService;

    @GetMapping("/{id}")
    public ApiResponse<ExampleReadResponse> read(@PathVariable Long id) {
        return ApiResponse.success(exampleService.read(id));
    }

    @PostMapping
    public ApiResponse<ExampleReadResponse> create(
            @Valid @RequestBody ExampleCreateOrUpdateRequest req) {
        return ApiResponse.created(exampleService.create(req));
    }

    @PutMapping("/{id}")
    public ApiResponse<ExampleReadResponse> update(
            @PathVariable Long id, @Valid @RequestBody ExampleCreateOrUpdateRequest req) {
        return ApiResponse.success(exampleService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteSoft(@PathVariable Long id) {
        exampleService.deleteSoft(id);
        return ApiResponse.successWithNoData();
    }
}
