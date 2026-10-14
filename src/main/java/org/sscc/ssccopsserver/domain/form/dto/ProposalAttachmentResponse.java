package org.sscc.ssccopsserver.domain.form.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.file.entity.FileReferenceEntity;

/**
 * 기획안 첨부 한 건 (server#685). 내려받기는 {@code …/attachments/{fileId}/download-url}로 그때그때 받는다 — 목록에 서명
 * URL을 싣지 않는다.
 *
 * <p><b>올린 사람을 싣지 않는다.</b> 운영 건 첨부(OperationAttachmentResponse)와 갈리는 자리다 — 기획안 첨부를 올리는 사람은 작성자 한
 * 명뿐이라 실을 정보가 없고, 승인 뒤에는 이 응답이 활동에 지원하는 회원 전체에게 나간다. 싣지 않아도 되는 회원 정보는 싣지 않는다.
 */
public record ProposalAttachmentResponse(
        Long fileId, String fileName, Long fileSize, OffsetDateTime uploadedAt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static ProposalAttachmentResponse of(FileReferenceEntity reference) {
        return new ProposalAttachmentResponse(
                reference.getId(),
                reference.getOriginalFileName(),
                reference.getFileSize(),
                toOffsetDateTime(reference.getCreatedAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
