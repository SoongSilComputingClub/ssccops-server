package org.sscc.ssccopsserver.global.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.sscc.ssccopsserver.domain.assistant.service.PgVectorRagChunkStore;
import org.sscc.ssccopsserver.domain.assistant.service.RagChunkStore;

/*
 * 규정 도우미(RAG)의 빈 배선 — 채팅 클라이언트와 청크 저장소 (#396 · ADR-0028).
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
}
