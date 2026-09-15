package org.sscc.ssccopsserver.support;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.Filter;
import org.sscc.ssccopsserver.domain.assistant.service.RagChunkStore;

/*
 * 골든셋용 청크 저장소 — **스텁 임베딩으로 실제 순위를 만든다** (#405 · 기획안 §14.2).
 *
 * ══ `InMemoryRagChunkStore`와 무엇이 다른가 ═════════════════════
 *
 * 그쪽은 **유사도를 흉내 내지 않는다** — 넣은 순서대로 `topK`개를 돌려줄 뿐이며, 그것이 맞다:
 * «검색이 무엇을 골랐나»가 아니라 «서비스가 무엇을 했나»를 보는 테스트가 스텁의 순위 규칙을
 * 검증하게 되면 안 된다.
 *
 * **골든셋은 정확히 그 «검색이 무엇을 골랐나»를 재는 자리라 순위가 있어야 한다.** hit@5도 거절도
 * 점수 없이는 말이 되지 않는다(임계값을 넘는 청크가 없다 = 거절이다). 그래서 이 클래스가 따로 있다.
 *
 * ══ 무엇을 임베딩이라 부르는가 ══════════════════════════════════
 *
 * **문자 bigram TF-IDF 벡터의 코사인**이다. 한국어라 형태소 분석기 없이 쓸 수 있는 자질이
 * 문자 n-gram이고, 사전이 필요 없어 **결정적**이다 — Gemini를 부르지 않는다는 것이 이 골든셋의
 * 전제다(§14.2 · 외부 의존이고 비결정적인 것은 CI에 넣지 않는다).
 *
 * ⚠️ **절대 점수의 눈금이 Gemini와 같지 않다.** 짧은 질문과 450자 조문 사이의 bigram 코사인은
 * 0.1~0.4 언저리에 모이고, 밀집 임베딩은 같은 관련도에서 훨씬 높은 값을 낸다. 그래서
 * **운영 기본 임계값(`ssccops.assistant.query.similarity-threshold*`)을 이 스텁의 분포로 정할 수
 * 없다** — 이 스텁이 답할 수 있는 것은 «관련 있는 것과 없는 것이 갈리는가»와 «유형별로 어느 쪽이
 * 낮게 나오는가»까지다. 실제 눈금은 실제 키로 재며 그 자리가 `./gradlew geminiCheck`다.
 *
 * ══ 필터는 흉내가 아니라 실제로 건다 ════════════════════════════
 *
 * 검색 필터(`ragDocId in [...]`)를 무시하면 «`INDEXED`가 아닌 판본의 청크가 결과에 없다»를 보는
 * 골든셋이 통과하는데 아무것도 검증하지 못한다. 서비스가 만드는 모양(IN 하나)만 다루고 모르는
 * 모양은 **조용히 통과시키지 않고 던진다** — 필터가 바뀌면 이 클래스가 먼저 알아야 한다.
 */
public class LexicalRagChunkStore implements RagChunkStore {

    private final List<Document> chunks = new ArrayList<>();

    /** 문서 빈도. 청크가 늘고 줄 때마다 버리고 다시 센다 — 테스트는 전부 넣은 뒤에 검색한다 */
    private Map<String, Integer> documentFrequency;

    @Override
    public void add(List<Document> newChunks) {
        chunks.addAll(newChunks);
        documentFrequency = null;
    }

    @Override
    public void deleteByRagDocumentId(long ragDocumentId) {
        chunks.removeIf(chunk -> Objects.equals(ragDocumentIdOf(chunk), ragDocumentId));
        documentFrequency = null;
    }

    @Override
    public List<Document> search(SearchRequest request) {
        return scored(request.getQuery()).stream()
                .filter(scored -> matches(request.getFilterExpression(), scored.chunk()))
                .filter(scored -> scored.score() >= request.getSimilarityThreshold())
                .limit(request.getTopK())
                .map(Scored::document)
                .toList();
    }

    /**
     * 질문 하나에 대한 <b>모든 청크의 점수</b>를 높은 순으로.
     *
     * <p>골든셋이 점수 <b>분포</b>를 보는 자리다 — 임계값을 «정한다»는 것은 관련 있는 것의 최저점과 없는 것의 최고점 사이에 틈이 있는지를 재는 일이고, 그
     * 둘은 검색이 잘라 낸 뒤에는 보이지 않는다.
     */
    public List<Scored> scored(String query) {
        Map<String, Double> queryVector = vector(query);
        List<Scored> all = new ArrayList<>(chunks.size());
        for (Document chunk : chunks) {
            all.add(new Scored(chunk, cosine(queryVector, vector(text(chunk)))));
        }
        all.sort(Comparator.comparingDouble(Scored::score).reversed());
        return List.copyOf(all);
    }

    /** 지금 들어 있는 청크 */
    public List<Document> chunks() {
        return List.copyOf(chunks);
    }

    public void clear() {
        chunks.clear();
        documentFrequency = null;
    }

    /** 청크 하나와 그 질문에 대한 점수. {@link #document()}가 점수를 실어 돌려준다 */
    public record Scored(Document chunk, double score) {

        /** 검색 결과로 나갈 모양 — <b>점수를 실어야</b> 서비스의 유형별 재판정이 값을 본다 */
        public Document document() {
            return Document.builder()
                    .id(chunk.getId())
                    .text(chunk.getText())
                    .metadata(chunk.getMetadata())
                    .score(score)
                    .build();
        }
    }

    // ------------------------------------------------------------------ 스텁 임베딩

    /*
     * TF-IDF — tf는 `1 + log(빈도)`, idf는 `log((N+1)/(df+1)) + 1`.
     *
     * **코퍼스에 없는 자질이 점수를 깎는 것이 요점이다.** 질문에만 있는 bigram은 어느 청크와도
     * 겹치지 않으면서 질문 벡터의 노름에는 들어가므로 코사인을 낮춘다 — 「코퍼스에 없는 주제」가
     * 임계값 아래로 내려가는 것이 그 성질이고, 거절률 1.0이 거기에 걸려 있다.
     */
    private Map<String, Double> vector(String text) {
        Map<String, Integer> counts = new HashMap<>();
        for (String feature : bigrams(text)) {
            counts.merge(feature, 1, Integer::sum);
        }
        Map<String, Integer> frequency = documentFrequency();
        int total = chunks.size();
        Map<String, Double> vector = new LinkedHashMap<>();
        counts.forEach(
                (feature, count) -> {
                    double tf = 1 + Math.log(count);
                    double idf =
                            Math.log((total + 1.0) / (frequency.getOrDefault(feature, 0) + 1.0))
                                    + 1;
                    vector.put(feature, tf * idf);
                });
        return vector;
    }

    private Map<String, Integer> documentFrequency() {
        if (documentFrequency == null) {
            Map<String, Integer> frequency = new HashMap<>();
            for (Document chunk : chunks) {
                for (String feature : Set.copyOf(bigrams(text(chunk)))) {
                    frequency.merge(feature, 1, Integer::sum);
                }
            }
            documentFrequency = frequency;
        }
        return documentFrequency;
    }

    /*
     * 자질 — **공백과 구두점을 걷어낸 뒤의 문자 bigram.**
     *
     * 한국어는 띄어쓰기가 흔들리고(«정회원 승격» · «정회원승격») 조사가 어미에 붙어 어절 단위로는
     * 거의 겹치지 않는다. 문자 2-gram은 그 둘을 같은 자질로 만든다 — 사전도 형태소 분석기도
     * 없이 결정적으로 도는 것이 이 골든셋이 요구하는 전부다.
     */
    private static List<String> bigrams(String text) {
        StringBuilder normalized = new StringBuilder(text.length());
        for (char character : text.toCharArray()) {
            if (Character.isLetterOrDigit(character)) {
                normalized.append(Character.toLowerCase(character));
            }
        }
        List<String> features = new ArrayList<>(Math.max(0, normalized.length() - 1));
        for (int at = 0; at + 1 < normalized.length(); at++) {
            features.add(normalized.substring(at, at + 2));
        }
        return features;
    }

    private static double cosine(Map<String, Double> left, Map<String, Double> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0d;
        }
        double dot = 0;
        for (Map.Entry<String, Double> entry : left.entrySet()) {
            Double other = right.get(entry.getKey());
            if (other != null) {
                dot += entry.getValue() * other;
            }
        }
        double norm = norm(left) * norm(right);
        return norm == 0 ? 0d : dot / norm;
    }

    private static double norm(Map<String, Double> vector) {
        double sum = 0;
        for (double weight : vector.values()) {
            sum += weight * weight;
        }
        return Math.sqrt(sum);
    }

    private static String text(Document chunk) {
        return chunk.getText() == null ? "" : chunk.getText();
    }

    // ------------------------------------------------------------------ 필터

    /*
     * 서비스가 거는 모양은 `ragDocId IN [...]` 하나다(`AssistantServiceImpl.retrieve`). AND·EQ 까지
     * 받아 두는 것은 조건이 하나 늘어도 이 클래스가 조용히 틀리지 않게 하기 위해서이고, 그 밖의
     * 모양은 **던진다** — 모르는 필터를 «통과»로 다루면 필터를 보는 골든셋이 아무것도 못 본다.
     */
    private static boolean matches(Filter.Expression expression, Document chunk) {
        if (expression == null) {
            return true;
        }
        return switch (expression.type()) {
            case AND ->
                    matches(operand(expression.left()), chunk)
                            && matches(operand(expression.right()), chunk);
            case IN ->
                    values(expression.right()).stream()
                            .anyMatch(value -> equalsAsString(value, metadata(expression, chunk)));
            case EQ -> equalsAsString(value(expression.right()), metadata(expression, chunk));
            default ->
                    throw new UnsupportedOperationException(
                            "골든셋 저장소가 모르는 검색 필터다 — " + expression.type());
        };
    }

    private static Object metadata(Filter.Expression expression, Document chunk) {
        if (expression.left() instanceof Filter.Key key) {
            return chunk.getMetadata().get(key.key());
        }
        throw new UnsupportedOperationException("필터 왼쪽이 메타데이터 key가 아니다 — " + expression.left());
    }

    private static Filter.Expression operand(Filter.Operand operand) {
        if (operand instanceof Filter.Group group) {
            return group.content();
        }
        if (operand instanceof Filter.Expression expression) {
            return expression;
        }
        throw new UnsupportedOperationException("AND 의 항이 식이 아니다 — " + operand);
    }

    private static Object value(Filter.Operand operand) {
        if (operand instanceof Filter.Value wrapped) {
            return wrapped.value();
        }
        throw new UnsupportedOperationException("필터 오른쪽이 값이 아니다 — " + operand);
    }

    private static List<?> values(Filter.Operand operand) {
        Object value = value(operand);
        return value instanceof List<?> list ? list : List.of(value);
    }

    /*
     * jsonb 왕복으로 수 타입이 바뀌는 자리라 실제 저장소도 문자열로 견준다(`RetrievedChunk` 주석).
     * 스텁이 `Long`끼리만 맞춰 보면 «로컬은 통과하는데 실제 PostgreSQL 에서만 0건»을 못 잡는다.
     */
    private static boolean equalsAsString(Object left, Object right) {
        return left != null && right != null && String.valueOf(left).equals(String.valueOf(right));
    }

    private static Long ragDocumentIdOf(Document chunk) {
        Object value = chunk.getMetadata().get(RAG_DOCUMENT_ID_KEY);
        return value instanceof Number number ? number.longValue() : null;
    }
}
