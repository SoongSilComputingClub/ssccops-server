package org.sscc.ssccopsserver.domain.form.dto;

/**
 * 기획안 첨부 내려받기 (server#685) — 원본 이름이 붙은 서명 GET URL({@code Content-Disposition: attachment})과 남은 시간.
 * 운영 건 첨부(OperationAttachmentDownloadResponse)와 같은 모양이며 화면은 받는 즉시 그 주소로 간다.
 */
public record ProposalAttachmentDownloadResponse(String url, long expiresInSeconds) {}
