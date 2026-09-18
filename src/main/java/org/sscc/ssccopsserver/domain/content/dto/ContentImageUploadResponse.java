package org.sscc.ssccopsserver.domain.content.dto;

/*
 * 갤러리 업로드 발급 응답 (ssccops#381). 행사(EventImageUploadResponse)의 다섯 필드에
 * **fileId**가 더해졌다 — 갤러리는 file_rfrnc 행이 있으므로 발급 순간 id가 생기고, 그 id가
 * 표지 지정(coverFileId)·삭제(DELETE …/images/{fileId})·익명 읽기 주소의 열쇠다.
 * uploadUrl로 PUT 하는 규칙(contentType 헤더는 응답의 값 그대로)은 행사와 같다.
 */
public record ContentImageUploadResponse(
        Long fileId,
        String uploadUrl,
        String imageUrl,
        String objectKey,
        String contentType,
        long expiresInSeconds) {}
