package org.sscc.ssccopsserver.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.sscc.ssccopsserver.domain.assistant.service.RagChunkStore;

/**
 * 테스트용 청크 저장소 (#396).
 *
 * <p><b>실제 PostgreSQL 없이 규정 도우미를 검증하기 위한 것이다.</b> {@code test} 프로필은 H2라 pgvector 자동 구성이 제외돼 있고
 * ({@code GeminiWiringEnvironmentPostProcessor}) 임베딩 모델도 없다. {@code @MockitoBean}으로 꽂지 않는 것은 그렇게 하면
 * 그 테스트마다 스프링 컨텍스트가 하나씩 갈리기 때문이다 — {@link AssistantStubConfig}를 import 하는 테스트들이 컨텍스트 하나를 나눠 쓴다.
 *
 * <p><b>유사도를 흉내 내지 않는다.</b> {@link #search}는 넣은 순서대로 {@code topK}개를 돌려준다 — 순위를 지어내면 «검색이 무엇을 골랐나»를
 * 확인하는 테스트가 스텁의 규칙을 검증하게 되고, 그 규칙은 pgvector의 것과 다르다. 검색 품질은 골든셋(#405)이 실제 스택에서 본다.
 */
public class InMemoryRagChunkStore implements RagChunkStore {

    private final List<Document> chunks = new ArrayList<>();

    @Override
    public void add(List<Document> newChunks) {
        chunks.addAll(newChunks);
    }

    @Override
    public void deleteByRagDocumentId(long ragDocumentId) {
        chunks.removeIf(
                chunk ->
                        Objects.equals(
                                String.valueOf(chunk.getMetadata().get(RAG_DOCUMENT_ID_KEY)),
                                String.valueOf(ragDocumentId)));
    }

    @Override
    public List<Document> search(SearchRequest request) {
        return List.copyOf(chunks.subList(0, Math.min(request.getTopK(), chunks.size())));
    }

    /** 지금 들어 있는 청크. 적재·재색인 테스트가 «무엇이 남았나»를 이것으로 본다 */
    public List<Document> chunks() {
        return List.copyOf(chunks);
    }

    /** 테스트 사이에 상태가 새지 않도록 비운다 — 컨텍스트를 나눠 쓰므로 이 빈은 클래스 경계를 넘어 산다 */
    public void clear() {
        chunks.clear();
    }
}
