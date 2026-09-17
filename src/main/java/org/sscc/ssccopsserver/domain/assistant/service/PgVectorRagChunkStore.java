package org.sscc.ssccopsserver.domain.assistant.service;

import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import lombok.RequiredArgsConstructor;

/*
 * `RagChunkStore`의 유일한 운영 구현 — Spring AI의 pgvector 저장소에 위임한다 (#396 · ADR-0028).
 *
 * **스키마는 이 클래스도 저장소도 만들지 않는다.** `spring.ai.vectorstore.pgvector.initialize-schema`
 * 가 false이고 `vector_store`는 `V10__create_assistant_tables.sql`이 만든다 — dev·prod가
 * `ddl-auto: validate`라 마이그레이션이 모르는 테이블이 생기면 안 되기 때문이다(ssccops#213).
 *
 * **빈은 `AssistantConfig`가 만들고, Gemini 키가 없으면 아예 없다.** 임베딩 모델 없이는 벡터
 * 저장소가 설 수 없기 때문이며(자동 구성이 `EmbeddingModel`을 생성자로 요구한다), 그 상태를
 * 부르는 쪽에 알리는 것은 `AssistantErrorCode.ASSISTANT_UNAVAILABLE`이다.
 */
@RequiredArgsConstructor
public class PgVectorRagChunkStore implements RagChunkStore {

    private final VectorStore vectorStore;

    @Override
    public void add(List<Document> chunks) {
        if (chunks.isEmpty()) {
            // 저장소에 빈 배치를 넘기면 구현에 따라 헛도는 UPDATE가 나간다. 부르는 쪽이
            // «청크가 0개인 문서»를 먼저 거절하므로(#399) 정상 흐름에서는 오지 않는다.
            return;
        }
        vectorStore.add(chunks);
    }

    /*
     * 필터 문자열을 손으로 잇지 않고 빌더를 쓴다 — 그 문자열은 JSON path로 번역되어 SQL에
     * 들어가므로, 값을 이어 붙이는 순간 우리가 만드는 유일한 주입 자리가 된다.
     */
    @Override
    public void deleteByRagDocumentId(long ragDocumentId) {
        vectorStore.delete(
                new FilterExpressionBuilder().eq(RAG_DOCUMENT_ID_KEY, ragDocumentId).build());
    }

    /*
     * 저장소는 결과가 없을 때 null을 돌려줄 수 있다(인터페이스가 @Nullable이다). 부르는 쪽마다
     * null을 살피게 두지 않고 여기서 빈 목록으로 굳힌다 — 답을 찾지 못한 질의는 정상 흐름이고
     * (§6.1 «근거가 없으면 부르지 않는다»), 그 자리에서 NPE가 나면 원인이 한 겹 멀어진다.
     */
    @Override
    public List<Document> search(SearchRequest request) {
        List<Document> found = vectorStore.similaritySearch(request);
        return found == null ? List.of() : found;
    }
}
