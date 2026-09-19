package org.sscc.ssccopsserver.domain.operation.dto;

/** 첨부 내려받기 — 원본 이름이 붙은 서명 URL과 남은 시간 (#493). 화면은 받는 즉시 그 주소로 간다 */
public record OperationAttachmentDownloadResponse(String url, long expiresInSeconds) {}
