package org.sscc.ssccopsserver.global.config;

import java.time.Clock;
import java.time.Duration;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.sscc.ssccopsserver.domain.assistant.service.AssistantMemoryStore;
import org.sscc.ssccopsserver.domain.assistant.service.PgVectorRagChunkStore;
import org.sscc.ssccopsserver.domain.assistant.service.QueryEmbeddingCache;
import org.sscc.ssccopsserver.domain.assistant.service.RagChunkStore;

/*
 * 규정 도우미(RAG)의 빈 배선 — 채팅 클라이언트 · 청크 저장소 · 대화 메모리 (#396 · #406 · ADR-0028).
 *
 * ── 조건이 왜 프로퍼티인가 ────────────────────────────────────
 *
 * 두 빈 다 스타터가 만드는 빈(`ChatModel`·`VectorStore`)을 받는데, 그 빈은 **Gemini API 키가
 * 있을 때만** 만들어진다. 그래서 «있으면 만들고 없으면 안 만든다»가 필요한데
 * **`@ConditionalOnBean`은 여기서 쓸 수 없다** — 사용자 `@Configuration`은 자동 구성보다 **먼저**
 * 평가되므로 그 조건은 언제나 false다(스프링에서 되풀이되는 함정이다).
 *
 * 대신 `GeminiWiringEnvironmentPostProcessor`가 그 «한 가지 사실»에서 파생해 둔 프로퍼티를 본다 —
 * 키가 없으면 그 클래스가 `spring.ai.model.chat`·`spring.ai.model.embedding.text`를 `none`으로
 * 두고 pgvector 자동 구성까지 제외한다. **판정이 두 벌이 되지 않는 것이 요점이며**, 키 없이도
 * 굳이 켜겠다고 명시한 설정이 있으면 그쪽이 이기고 부팅이 실패한다(명시적으로 요청한 결과다).
 *
 * ── 빈이 없을 때 ─────────────────────────────────────────────
 *
 * 규정 도우미 서비스는 `ObjectProvider`로 받거나 아예 플래그(`AssistantFeature`) 뒤에 있으므로
 * 그 상태가 다른 기능을 막지 않는다. 부르는 쪽에 알리는 코드는 503 `ASSISTANT_UNAVAILABLE`이다.
 */
@Configuration
public class AssistantConfig {

    /*
     * 규정 도우미의 채팅 클라이언트.
     *
     * **프롬프트를 여기에 굳히지 않는다.** 시스템 프롬프트·기본 옵션은 답변 계약(§6.2)에 딸린
     * 값이라 질의 서비스(#403)가 요청마다 얹는다 — 여기에 `defaultSystem(...)`을 두면 계약이
     * 설정과 서비스 두 곳에 살고, 그때 화면이 받는 답과 골든셋이 검증하는 답이 갈린다.
     */
    @Bean
    @ConditionalOnExpression("'${spring.ai.model.chat:}' != 'none'")
    ChatClient assistantChatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

    /*
     * 청크 저장소. `VectorStore` 빈은 pgvector 자동 구성이 만들며 **임베딩 모델을 생성자로
     * 요구하므로**, 임베딩 쪽 스위치를 그대로 본다.
     */
    @Bean
    @ConditionalOnExpression("'${spring.ai.model.embedding.text:}' != 'none'")
    RagChunkStore ragChunkStore(VectorStore vectorStore) {
        return new PgVectorRagChunkStore(vectorStore);
    }

    /*
     * 질의 임베딩 캐시 — **벡터 저장소가 받는 임베딩 모델 자리에 끼워 넣는다** (#406 · 기획안 §7.1).
     *
     * `@Primary`인 것은 저장소 자동 구성이 `EmbeddingModel`을 **타입으로** 주입받기 때문이다.
     * 감싸는 대상을 `EmbeddingModel`이 아니라 **구체 타입으로** 받는 것은 그러지 않으면 자기
     * 자신(우선 빈)을 주입받으려 하기 때문이며, 덤으로 스타터를 갈아 끼울 때 이 줄이 **컴파일
     * 단계에서** 걸린다 — 빈 이름 문자열로 묶으면 그 실패가 부팅 때로 미뤄진다.
     *
     * 조건은 청크 저장소와 같다 — 키가 없으면 감쌀 모델 자체가 없다.
     */
    @Bean
    @Primary
    @ConditionalOnExpression("'${spring.ai.model.embedding.text:}' != 'none'")
    EmbeddingModel assistantQueryEmbeddingModel(
            GoogleGenAiTextEmbeddingModel embeddingModel,
            @Value("${ssccops.assistant.query.embedding-cache-size:1000}") long maxSize,
            @Value("${ssccops.assistant.query.embedding-cache-ttl:P7D}") Duration ttl,
            Clock clock) {

        return new QueryEmbeddingCache(embeddingModel, maxSize, ttl, clock);
    }

    /*
     * 대화 메모리 (#406 · 기획안 §7).
     *
     * **조건이 없다 — Gemini 키와 무관하다.** 담는 것이 우리가 만든 문자열 둘뿐이라 모델이 없어도
     * 성립하고, 조건을 달면 «키가 없는 서버에서는 대화가 조용히 이어지지 않는» 상태가 하나 더
     * 생긴다(질의 자체가 이미 503으로 끊긴다).
     *
     * **빈으로 두는 이유는 자동 구성을 물리기 위해서다.** `ChatMemoryAutoConfiguration`이
     * `@ConditionalOnMissingBean`으로 `MessageWindowChatMemory`를 **기본 창(메시지 20개 =
     * 10턴)**으로 세워 둔다 — 두면 이름만 같은 창이 둘이 되고, 나중에 `ChatMemory`를 주입받는
     * 자리가 어느 쪽을 받는지가 주입 지점에 따라 갈린다. 같은 이유로 저장소 쪽 기본 빈
     * (`InMemoryChatMemoryRepository` · **상한이 없다**)은 `AssistantMemoryStore`가 물린다.
     *
     * 20턴은 §8.1의 산수에 들어간 값이다 — 대화 1개 ≈ 24KB의 «20턴 = 메시지 40개»가 여기다.
     */
    @Bean
    ChatMemory assistantChatMemory(
            AssistantMemoryStore memoryStore,
            @Value("${ssccops.assistant.memory.max-turns:20}") int maxTurns) {

        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(memoryStore)
                .maxMessages(maxTurns * 2)
                .build();
    }
}
