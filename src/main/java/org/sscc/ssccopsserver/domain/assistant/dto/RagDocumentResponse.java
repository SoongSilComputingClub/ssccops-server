package org.sscc.ssccopsserver.domain.assistant.dto;

import java.time.Instant;
import java.time.LocalDate;

import org.sscc.ssccopsserver.domain.assistant.code.RagApplyStatus;
import org.sscc.ssccopsserver.domain.assistant.code.RagDocumentType;
import org.sscc.ssccopsserver.domain.assistant.code.RagIndexStatus;
import org.sscc.ssccopsserver.domain.assistant.entity.RagDocumentEntity;

/*
 * 규정 문서 판본 한 행 (#399 · #401 · 기획안 §13.2).
 *
 * **목록의 한 행이 곧 업로드·재색인·적용 전환의 응답이다.** 업로드가 201 + `PENDING`인 이유가
 * «행이 실제로 생겼으니 화면이 그것으로 목록의 «대기» 행을 즉시 그린다»인데(#399), 그 말이
 * 성립하려면 업로드 응답이 **목록 한 행과 같은 모양**이어야 한다 — 두 record로 두면 필드가 늘
 * 때 한쪽만 늘어 갈리고, 그때 화면은 «업로드 직후의 행»과 «다시 받은 목록의 행»을 다르게
 * 그리게 된다. 전환·재색인도 바뀐 행 하나를 돌려주므로 같은 자리다.
 *
 * `chunkCount`는 색인이 끝나야 채워진다 — 그 전에는 null이고 화면은 그 자리에 «—»를 그린다.
 * `failureReason`은 `FAILED`일 때만 있고(워커에는 돌려줄 응답이 없어 이 컬럼이 오류 코드의
 * 자리를 대신한다 · §12.4) 화면은 실패 행의 툴팁에 그것을 싣는다. `effectiveFrom`은
 * `DRAFT`에 비어 있는 것이 정상이다 — 의결 전 개정안에는 발효일이 없다(#396 엔티티 주석).
 */
public record RagDocumentResponse(
        Long ragDocId,
        String name,
        RagDocumentType docType,
        RagIndexStatus indexStatus,
        RagApplyStatus applyStatus,
        String originalFileName,
        Integer fileSize,
        Integer chunkCount,
        String failureReason,
        LocalDate effectiveFrom,
        Instant createdAt) {

    public static RagDocumentResponse from(RagDocumentEntity document) {
        return new RagDocumentResponse(
                document.getId(),
                document.getName(),
                document.getType(),
                document.getIndexStatus(),
                document.getApplyStatus(),
                document.getOriginalFileName(),
                document.getFileSize(),
                document.getChunkCount(),
                document.getFailureReason(),
                document.getEffectiveFrom(),
                document.getCreatedAt());
    }
}
