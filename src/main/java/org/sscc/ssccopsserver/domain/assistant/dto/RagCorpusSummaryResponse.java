package org.sscc.ssccopsserver.domain.assistant.dto;

/*
 * 코퍼스 요약 3값 — 화면의 카드 셋 (#401 · 기획안 §10 · §13.2).
 *
 * **목록 응답에 함께 실린다 — 별도 엔드포인트가 아니다.** 화면이 카드와 표를 언제나 함께
 * 그리는데 나누면 두 요청 사이에 색인이 끝나 **카드와 표가 다른 시점을 가리킨다**(폼 상세가
 * `responseSummary`를 함께 내리는 것과 같은 자리 · #37).
 *
 * **검색어(`q`)가 있어도 이 셋은 코퍼스 전체다.** 카드가 답하는 질문이 «지금 코퍼스에 무엇이
 * 있나»이지 «검색 결과가 몇 건인가»가 아니기 때문이다 — 검색으로 카드가 함께 줄면 운영진이
 * 필터를 건 채 «등록 문서 1건»을 읽는다.
 *
 * `totalChunkCount`는 **활성 청크**다(`INDEXED && 적용 상태 ≠ SUPERSEDED` ·
 * `RagDocumentRepository.sumActiveChunkCount`). 색인 워커가 상한 3,000을 판정할 때 보는 값과
 * **같은 수**여야 하기 때문이며(#400), 갈리면 화면에 «2,900»이 떠 있는데 다른 수를 근거로
 * `RAG_DOCUMENT_LIMIT_EXCEEDED`가 난다. 그래서 «세는 자리는 한 곳»이 여기까지 이어진다.
 */
public record RagCorpusSummaryResponse(
        long registeredCount, long indexedCount, long totalChunkCount) {}
