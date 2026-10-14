package org.sscc.ssccopsserver.domain.form.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 기획안 첨부 업로드 허가 요청 (server#685 · ssccops#607) — 원본 파일 이름(확장자 포함)과 바이트 크기. 형식은 이름의 확장자로
 * 판정한다(pdf·txt·md). 크기는 서명에 Content-Length로 들어가므로 실제 파일과 다르면 R2가 PUT을 거절한다 — 그래서 합계 상한을 이 신고값으로 셀 수
 * 있다.
 */
public record ProposalAttachmentUploadRequest(
        @NotBlank @Size(max = 255) String fileName, @NotNull @Positive Long fileSize) {}
