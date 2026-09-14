package org.sscc.ssccopsserver.domain.assistant.dto;

import java.time.Instant;

import org.sscc.ssccopsserver.domain.assistant.code.RagApplyStatus;
import org.sscc.ssccopsserver.domain.assistant.code.RagDocumentType;
import org.sscc.ssccopsserver.domain.assistant.code.RagIndexStatus;
import org.sscc.ssccopsserver.domain.assistant.entity.RagDocumentEntity;

/*
 * 규정 문서 업로드 응답 (#399 · POST /v1/assistant/documents).
 *
 * **201이고 `indexStatus`는 언제나 `PENDING`이다.** 202가 아닌 것은 행이 실제로 생겼기
 * 때문이며, 그래서 화면은 이 응답만으로 목록의 행 하나를 그릴 수 있다 — 목업의 표에 «대기»
 * 배지가 달린 행이 즉시 나타난다(기획안 §13.2). 202는 «받아 두었으나 아직 아무것도 없다»는
 * 뜻이라 화면이 그릴 것이 없어진다.
 *
 * **그래서 필드가 목록 한 행이 필요로 하는 값 그대로다** — 문서명·크기·청크·등록일·상태·적용.
 * `chunkCount`는 색인이 끝나야 채워지므로 여기서는 언제나 null이고, 화면은 그 자리에 «—»를
 * 그린다(#396 엔티티 주석).
 */
public record RagDocumentUploadResponse(
        Long ragDocId,
        String documentCode,
        String name,
        RagDocumentType docType,
        Short version,
        RagIndexStatus indexStatus,
        RagApplyStatus applyStatus,
        String originalFileName,
        Integer fileSize,
        Integer chunkCount,
        Instant createdAt) {

    public static RagDocumentUploadResponse from(RagDocumentEntity document) {
        return new RagDocumentUploadResponse(
                document.getId(),
                document.getDocumentCode(),
                document.getName(),
                document.getType(),
                document.getVersion(),
                document.getIndexStatus(),
                document.getApplyStatus(),
                document.getOriginalFileName(),
                document.getFileSize(),
                document.getChunkCount(),
                document.getCreatedAt());
    }
}
