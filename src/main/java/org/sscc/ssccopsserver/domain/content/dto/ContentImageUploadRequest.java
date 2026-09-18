package org.sscc.ssccopsserver.domain.content.dto;

import java.util.Locale;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/*
 * 갤러리 업로드 발급 요청 (ssccops#381). 행사 이미지(EventImageUploadRequest · #210)와 같은
 * 계약이다 — 요청이 신고하는 것은 확장자와 크기뿐이고 형식(contentType)은 서버가 정한다.
 * 그쪽 record를 그대로 쓰지 않은 것은 도메인이 다른 도메인의 요청 DTO를 빌리면 그쪽 변경이
 * 이쪽 API 계약을 바꾸기 때문이다.
 */
public record ContentImageUploadRequest(
        @NotBlank @Size(max = 10) String fileExt, @NotNull @Positive Long fileSize) {

    public String normalizedFileExt() {
        if (fileExt == null) {
            return "";
        }
        String trimmed = fileExt.trim().toLowerCase(Locale.ROOT);
        return trimmed.startsWith(".") ? trimmed.substring(1) : trimmed;
    }
}
