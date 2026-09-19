package org.sscc.ssccopsserver.domain.operation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 첨부 업로드 허가 요청 (#493) — 원본 파일 이름(확장자 포함)과 크기. 형식은 이름의 확장자로 판정한다 */
public record OperationAttachmentUploadRequest(
        @NotBlank @Size(max = 255) String fileName, @NotNull @Positive Long fileSize) {}
