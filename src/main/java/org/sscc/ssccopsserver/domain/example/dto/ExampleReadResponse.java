package org.sscc.ssccopsserver.domain.example.dto;

import org.sscc.ssccopsserver.domain.example.entity.ExampleStatus;

// 예시 조회 응답 DTO
public record ExampleReadResponse(Long id, String title, String content, ExampleStatus status) {}
