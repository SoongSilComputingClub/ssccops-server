package org.sscc.ssccopsserver.domain.operation.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.file.entity.FileReferenceEntity;

/** 첨부 한 건 (#493). 내려받기는 `GET …/attachments/{fileId}/download`(서명 URL로 302) */
public record OperationAttachmentResponse(
        Long fileId,
        String fileName,
        Long fileSize,
        MemberSummaryResponse uploader,
        OffsetDateTime uploadedAt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static OperationAttachmentResponse of(
            FileReferenceEntity reference, MemberSummaryResponse uploader) {
        return new OperationAttachmentResponse(
                reference.getId(),
                reference.getOriginalFileName(),
                reference.getFileSize(),
                uploader,
                toOffsetDateTime(reference.getCreatedAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
