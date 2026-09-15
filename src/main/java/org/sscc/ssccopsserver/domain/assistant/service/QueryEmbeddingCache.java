package org.sscc.ssccopsserver.domain.assistant.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Scheduler;

import lombok.extern.slf4j.Slf4j;

/*
 * 질의 임베딩 캐시 — 같은 질문을 두 번 임베딩하지 않는다 (#406 · 기획안 §7.1 · §8.1).
 *
 * ══ 무엇을 아끼는가 ════════════════════════════════════════════
 *
 * 질의 한 건은 Gemini를 **두 번** 부른다 — 질문 임베딩과 생성이다(#404). 앞의 것은 같은 문장에
 * 같은 답이므로 두 번 부를 이유가 없고, 실제로 같은 문장이 반복해서 들어온다: **추천 질문
 * 셋**(§13.3)은 화면이 내려 주는 정해진 문장이라 여러 사람이 같은 것을 누른다. 무료 쿼터는 API
 * 키 단위의 공유 자원이라(§11) 그만큼이 전원의 몫으로 남는다.
 *
 * ══ 왜 `EmbeddingModel`을 감싸는가 ══════════════════════════════
 *
 * 임베딩을 부르는 쪽이 우리가 아니라 **벡터 저장소 안쪽**이기 때문이다 — `SearchRequest`는 질문
 * 문자열만 받고 미리 계산한 벡터를 실을 자리가 없어, 캐시를 걸 수 있는 유일한 이음매가 저장소가
 * 쥐고 있는 `EmbeddingModel`이다. 그래서 이 데코레이터를 `@Primary`로 세우고(`AssistantConfig`)
 * 저장소가 그것을 받게 한다.
 *
 * **캐시는 `embed(String)` 한 자리에만 건다.** 그 자리가 곧 «질의 한 건»이다 —
 * `PgVectorStore.getQueryEmbedding`이 부르는 것이 이 메서드이고, 색인은
 * `embed(List<Document>, …)`로 배치째 들어온다(그 텍스트는 같은 모양으로 다시 오지 않으므로
 * 캐시해 봐야 자리만 차지하고, 1,000칸을 청크로 채워 **질문을 밀어낸다**). 나머지 메서드는
 * 전부 그대로 넘긴다 — 감싸는 쪽이 동작을 바꾸지 않는다는 것이 이 클래스의 계약이다.
 *
 * ⚠️ **`dimensions()`도 넘긴다.** 모델 클래스의 그 값은 이름 상수표를 먼저 보고 실제 벡터와 다른
 * 수를 답할 수 있는데(#395 실측), 여기서 «고쳐» 주면 그 사실이 한 겹 더 가려진다. 차원을 못
 * 박는 자리는 `spring.ai.vectorstore.pgvector.dimensions`이고 그것은 `V10`의 `vector(768)`과
 * 짝을 이룬다.
 *
 * ══ 키가 질문 원문이 아니다 ═════════════════════════════════════
 *
 * **SHA-256 다이제스트를 키로 쓴다.** 값이 7일을 사는 캐시라 원문을 키로 두면 **그 기간 동안
 * 힙에 질문이 남는다** — 질문에는 사람 이름이 섞여 들어올 수 있어 로그에도 싣지 않기로 한
 * 값이다(§11 · ADR-0024). 다이제스트는 §8.1이 잡아 둔 크기 계산(키 64자 + `float[768]`)과도
 * 같다. 대화 메모리(`AssistantMemoryStore`)가 질문 원문을 담는 것과 갈리는 지점인데, 그쪽은
 * 담지 않으면 «이어 가기» 자체가 성립하지 않고 수명이 24시간 슬라이딩이다.
 *
 * ══ 만료는 고정이다 ════════════════════════════════════════════
 *
 * `expireAfterWrite(7d)`이며 대화 메모리의 슬라이딩(`expireAfterAccess`)과 갈린다 — 자주 묻는
 * 질문이라고 벡터를 영원히 들고 있을 이유가 없고, **임베딩 모델을 바꾸면 그 값이 곧 옛 눈금**이다
 * (`GEMINI_EMBEDDING_MODEL` 한 줄로 바뀐다 · #395). 상한(`maximumSize`)이 메모리를 보증하고
 * TTL은 위생이라는 것은 여기서도 같다(`AssistantMemoryStore`의 ⚠️).
 */
@Slf4j
public class QueryEmbeddingCache implements EmbeddingModel {

    private final EmbeddingModel delegate;

    private final Cache<String, float[]> embeddings;

    public QueryEmbeddingCache(EmbeddingModel delegate, long maxSize, Duration ttl, Clock clock) {
        this.delegate = delegate;
        this.embeddings =
                Caffeine.newBuilder()
                        .maximumSize(maxSize)
                        .expireAfterWrite(ttl)
                        .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis()))
                        .scheduler(Scheduler.systemScheduler())
                        .build();

        log.info("규정 도우미 질의 임베딩 캐시 — {}개 · 쓴 뒤 {}", maxSize, ttl);
    }

    /**
     * 질문 하나의 벡터 — <b>캐시가 걸리는 유일한 자리다</b>.
     *
     * <p>{@code Cache.get}의 적재 함수 안에서 부르므로 <b>같은 질문이 동시에 들어오면 한 번만 부른다</b> — 전역 분 한도(#404) 아래에서도 같은
     * 추천 질문이 겹칠 수 있는 자리다.
     *
     * <p>넣을 때도 꺼낼 때도 <b>복사한다</b>. 부르는 쪽이 받은 배열을 손대면 캐시에 든 값이 함께 바뀌는데, 그러면 그 질문의 검색 결과가 조용히 달라진다.
     */
    @Override
    public float[] embed(String text) {
        return embeddings.get(digest(text), key -> delegate.embed(text).clone()).clone();
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        return delegate.call(request);
    }

    @Override
    public float[] embed(Document document) {
        return delegate.embed(document);
    }

    @Override
    public String getEmbeddingContent(Document document) {
        return delegate.getEmbeddingContent(document);
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        return delegate.embed(texts);
    }

    /** 색인이 지나는 길 — 배치째 들어오므로 캐시하지 않는다(클래스 주석) */
    @Override
    public List<float[]> embed(
            List<Document> documents, EmbeddingOptions options, BatchingStrategy batchingStrategy) {
        return delegate.embed(documents, options, batchingStrategy);
    }

    @Override
    public EmbeddingResponse embedForResponse(List<String> texts) {
        return delegate.embedForResponse(texts);
    }

    @Override
    public int dimensions() {
        return delegate.dimensions();
    }

    /*
     * 질문 원문을 키로 두지 않기 위한 한 줄(위 «키가 질문 원문이 아니다»). SHA-256은 JDK가
     * 반드시 싣는 알고리즘이라 `NoSuchAlgorithmException`은 일어나지 않는다 — 그래도 삼키지
     * 않는 것은, 일어난다면 그것이 이 캐시의 문제가 아니라 런타임이 깨진 상태이기 때문이다.
     */
    private String digest(String text) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256을 찾을 수 없다", impossible);
        }
    }
}
