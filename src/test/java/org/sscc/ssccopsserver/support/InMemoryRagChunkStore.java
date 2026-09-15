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
 * 확인하는 테스트가 스텁의 규칙을 검증하게 되고, 그 규칙은 pgvector의 것과 다르다. 순위가 필요한 골든셋(#405)은 스텁 임베딩으로 실제 점수를 만드는 {@link
 * LexicalRagChunkStore}를 따로 쓴다.
 *
 * <p><b>«언제 무엇이 불렸나»를 함께 적는다</b>(#405 · 기획안 §14.3). {@link #operations()}가 적재·삭제의 순서를 들고 있어 두 가지를
 * 같은 자리에서 본다 — <b>업로드는 저장소를 건드리지 않는다</b>(임베딩을 부르는 자리가 {@link #add}뿐이라 빈 목록이 곧 「임베딩 호출 0」이다)와
 * <b>재색인은 넣기 전에 지운다</b>(순서를 뒤집으면 중간에 실패했을 때 같은 조가 두 번 검색된다).
 */
public class InMemoryRagChunkStore implements RagChunkStore {

    private final List<Document> chunks = new ArrayList<>();

    /** 저장소에 닿은 순서 — `add:40` · `delete:7` */
    private final List<String> operations = new ArrayList<>();

    /** 적재 <b>도중에</b> 끼어드는 관찰자. 「임베딩이 도는 동안 그 행이 {@code INDEXING}인가」를 보는 자리다 */
    private Runnable duringAdd;

    @Override
    public void add(List<Document> newChunks) {
        operations.add("add:" + newChunks.size());
        if (duringAdd != null) {
            duringAdd.run();
        }
        chunks.addAll(newChunks);
    }

    @Override
    public void deleteByRagDocumentId(long ragDocumentId) {
        operations.add("delete:" + ragDocumentId);
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

    /**
     * 적재·삭제가 불린 순서.
     *
     * <p><b>비어 있음이 「임베딩을 한 번도 부르지 않았다」는 뜻이다</b> — 모델 호출이 일어나는 자리가 {@link #add} 하나뿐이라(실제 구현에서는 벡터
     * 저장소가 거기서 임베딩을 부른다) 업로드 경로가 이 목록을 비운 채로 끝나는 것이 #399의 계약이다.
     */
    public List<String> operations() {
        return List.copyOf(operations);
    }

    /** 적재 도중에 부를 것을 걸어 둔다. {@link #clear()}가 함께 걷어낸다 */
    public void observeAdds(Runnable observer) {
        this.duringAdd = observer;
    }

    /** 테스트 사이에 상태가 새지 않도록 비운다 — 컨텍스트를 나눠 쓰므로 이 빈은 클래스 경계를 넘어 산다 */
    public void clear() {
        chunks.clear();
        operations.clear();
        duringAdd = null;
    }
}
