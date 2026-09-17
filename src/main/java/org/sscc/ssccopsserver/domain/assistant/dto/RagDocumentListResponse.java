package org.sscc.ssccopsserver.domain.assistant.dto;

import java.util.List;

/*
 * 목록 응답 — 표 + 카드 셋 (#401 · `GET /v1/assistant/documents`).
 *
 * 요약을 함께 싣는 이유는 `RagCorpusSummaryResponse`에 있다. `documents`는 **최신 업로드
 * 순**이고 페이징이 없다 — 이 표는 «문서 종류 × 판본»이라 행이 수십 단위다(`V10` 하단).
 */
public record RagDocumentListResponse(
        RagCorpusSummaryResponse summary, List<RagDocumentResponse> documents) {

    public RagDocumentListResponse {
        documents = List.copyOf(documents);
    }
}
