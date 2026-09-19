package org.sscc.ssccopsserver.domain.operation.dto;

/**
 * 첨부 업로드 허가 (#493). 콘텐츠 이미지 발급과 같은 계약 — 웹은 `contentType`을 그대로 PUT 헤더에 쓰고 (서명에 들어 있다), PUT이 실패하면
 * `fileId`를 DELETE로 치운다.
 */
public record OperationAttachmentUploadResponse(
        Long fileId, String uploadUrl, String contentType, long expiresInSeconds) {}
