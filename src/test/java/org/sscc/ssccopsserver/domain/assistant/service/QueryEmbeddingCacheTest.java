package org.sscc.ssccopsserver.domain.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/*
 * 질의 임베딩 캐시 — **무엇을 캐시하고 무엇을 그냥 넘기는가** (#406 · 기획안 §7.1).
 *
 * 질의 한 건은 Gemini를 두 번 부르고(임베딩·생성) 그중 앞의 것은 같은 문장에 같은 답이다.
 * 실제로 같은 문장이 반복해서 들어오는 자리가 있다 — **추천 질문 셋**은 서버가 내려 주는 정해진
 * 문장이라 여러 사람이 같은 것을 누른다(§13.3).
 *
 * ⚠️ **캐시가 걸리는 자리는 `embed(String)` 하나뿐이다.** 그 자리가 곧 «질의 한 건»이고
 * (`PgVectorStore.getQueryEmbedding`이 부른다), 색인은 배치 메서드로 들어와 캐시를 지나지
 * 않는다 — 청크로 1,000칸을 채우면 **질문이 밀려난다.** 그 경계를 아래 둘이 못 박는다.
 */
class QueryEmbeddingCacheTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private static final Duration WEEK = Duration.ofDays(7);

    private final MovableClock clock =
            new MovableClock(ZonedDateTime.of(2026, 9, 15, 10, 30, 0, 0, SEOUL).toInstant());

    private final CountingEmbeddingModel delegate = new CountingEmbeddingModel();

    // ------------------------------------------------------------------ 캐시가 걸리는 자리

    /* 같은 질문은 한 번만 임베딩한다 — 아끼는 것이 무료 쿼터다(§11) */
    @Test
    void embedsTheSameQuestionOnlyOnce() {
        QueryEmbeddingCache cache = cache(1_000, WEEK);

        float[] first = cache.embed("정회원 승격 조건은?");
        float[] second = cache.embed("정회원 승격 조건은?");

        assertThat(delegate.calls).isOne();
        assertThat(second).isEqualTo(first);
    }

    /* 다른 질문은 다른 벡터다 — 캐시가 질문을 뭉개지 않는다 */
    @Test
    void embedsEachDistinctQuestion() {
        QueryEmbeddingCache cache = cache(1_000, WEEK);

        cache.embed("정회원 승격 조건은?");
        cache.embed("총회는 언제 열리나요?");

        assertThat(delegate.calls).isEqualTo(2);
    }

    /*
     * **꺼낸 배열을 손대도 캐시가 바뀌지 않는다.** 바뀌면 그 질문의 검색 결과가 조용히 달라지는데,
     * 원인이 «누가 남의 배열을 제자리에서 고쳤다»라 어디서도 드러나지 않는다.
     */
    @Test
    void handsOutACopyOfTheCachedVector() {
        QueryEmbeddingCache cache = cache(1_000, WEEK);

        float[] handed = cache.embed("정회원 승격 조건은?");
        handed[0] = 42.0f;

        assertThat(cache.embed("정회원 승격 조건은?")[0]).isEqualTo(0.5f);
        assertThat(delegate.calls).isOne();
    }

    /*
     * **만료는 고정이다**(`expireAfterWrite`) — 대화 메모리의 슬라이딩과 갈린다. 자주 묻는
     * 질문이라고 벡터를 영원히 들고 있을 이유가 없고, 임베딩 모델을 바꾸면
     * (`GEMINI_EMBEDDING_MODEL` 한 줄 · #395) 그 값이 곧 옛 눈금이다.
     */
    @Test
    void forgetsAVectorAWeekAfterItWasWritten() {
        QueryEmbeddingCache cache = cache(1_000, WEEK);

        cache.embed("정회원 승격 조건은?");
        clock.advance(Duration.ofDays(6));
        cache.embed("정회원 승격 조건은?");
        assertThat(delegate.calls).as("읽었다고 수명이 늘지 않는다").isOne();

        clock.advance(Duration.ofDays(2));
        cache.embed("정회원 승격 조건은?");
        assertThat(delegate.calls).isEqualTo(2);
    }

    // ------------------------------------------------------------------ 그냥 넘기는 자리

    /*
     * **색인은 캐시를 지나지 않는다.** 같은 청크를 두 번 적재하는 일은 재색인뿐이고(#400), 그때
     * 쓰려고 1,000칸을 문서로 채우면 정작 질문이 밀려난다.
     */
    @Test
    void doesNotCacheTheIndexingPath() {
        QueryEmbeddingCache cache = cache(1_000, WEEK);
        List<Document> chunks = List.of(new Document("제7조 (회원의 구분)"));

        cache.embed(chunks, EmbeddingOptions.builder().build(), new StubBatchingStrategy());
        cache.embed(chunks, EmbeddingOptions.builder().build(), new StubBatchingStrategy());

        assertThat(delegate.batchCalls).isEqualTo(2);
        assertThat(delegate.calls).isZero();
    }

    /*
     * ⚠️ **`dimensions()`도 그냥 넘긴다.** 모델 클래스의 그 값은 이름 상수표를 먼저 보고 실제
     * 벡터와 다른 수를 답할 수 있는데(#395 실측), 여기서 «고쳐» 주면 그 사실이 한 겹 더 가려진다.
     */
    @Test
    void passesEverythingElseStraightThrough() {
        QueryEmbeddingCache cache = cache(1_000, WEEK);

        assertThat(cache.dimensions()).isEqualTo(768);
        assertThat(cache.call(new EmbeddingRequest(List.of("질문"), null))).isNotNull();
        assertThat(delegate.responseCalls).isOne();
    }

    // ------------------------------------------------------------------ 픽스처

    private QueryEmbeddingCache cache(long maxSize, Duration ttl) {
        return new QueryEmbeddingCache(delegate, maxSize, ttl, clock);
    }

    /** 부른 횟수가 이 테스트의 관측값이다 — 캐시의 값어치가 그 수의 차이다 */
    private static final class CountingEmbeddingModel implements EmbeddingModel {

        private int calls;
        private int batchCalls;
        private int responseCalls;

        @Override
        public float[] embed(String text) {
            calls++;
            return new float[] {0.5f, 0.25f};
        }

        @Override
        public float[] embed(Document document) {
            batchCalls++;
            return new float[] {0.5f, 0.25f};
        }

        @Override
        public List<float[]> embed(
                List<Document> documents,
                EmbeddingOptions options,
                BatchingStrategy batchingStrategy) {

            batchCalls++;
            List<float[]> embeddings = new ArrayList<>();
            documents.forEach(document -> embeddings.add(new float[] {0.5f, 0.25f}));
            return embeddings;
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            responseCalls++;
            return new EmbeddingResponse(List.of());
        }

        @Override
        public int dimensions() {
            return 768;
        }
    }

    /** 배치 전략은 이 테스트가 보는 값이 아니다 — 넘기기만 한다 */
    private static final class StubBatchingStrategy implements BatchingStrategy {

        @Override
        public List<List<Document>> batch(List<Document> documents) {
            return List.of(documents);
        }
    }

    private static final class MovableClock extends Clock {

        private Instant now;

        private MovableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return SEOUL;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("시간대를 바꿔 쓰지 않는다");
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
