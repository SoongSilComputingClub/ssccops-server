package org.sscc.ssccopsserver.domain.form.dto;

/**
 * 기획안 첨부 업로드 허가 (server#685). 운영 건 첨부(OperationAttachmentUploadResponse)와 같은 모양 — 웹은 {@code
 * contentType}을 그대로 PUT 헤더에 쓰고(서명에 들어 있다), PUT이 실패하면 {@code fileId}를 DELETE로 치운다. 실패한 참조도 지울 때까지
 * 개수·용량을 차지한다.
 */
public record ProposalAttachmentUploadResponse(
        Long fileId, String uploadUrl, String contentType, long expiresInSeconds) {}
