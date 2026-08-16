package org.sscc.ssccopsserver.domain.example.dto;

import jakarta.validation.constraints.NotBlank;

// 예시 생성/수정 요청 DTO
public record ExampleCreateOrUpdateRequest(@NotBlank String title, String content) {}
