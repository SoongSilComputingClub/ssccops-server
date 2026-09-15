package org.sscc.ssccopsserver.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.sscc.ssccopsserver.domain.assistant.service.AssistantMemoryStore;
import org.sscc.ssccopsserver.domain.assistant.service.PgVectorRagChunkStore;
import org.sscc.ssccopsserver.domain.assistant.service.QueryEmbeddingCache;
import org.sscc.ssccopsserver.domain.assistant.service.RagChunkStore;

/*
 * 어느 빈이 «모델이 있을 때만» 서는지 (#396 · #406).
 *
 * **이 테스트가 막는 것은 조건을 `@ConditionalOnBean`으로 되돌리는 것이다.** 사용자
 * `@Configuration`은 자동 구성보다 **먼저** 평가되므로 `@ConditionalOnBean(ChatModel.class)`는
 * 언제나 false다 — 컴파일도 되고 테스트도 «빈이 없다» 쪽만 보면 통과하는데, 키를 넣은 배포에서
 * 규정 도우미가 조용히 서지 않는다. 그래서 **켜지는 쪽을 함께 본다.**
 *
 * 조건의 재료는 `GeminiWiringEnvironmentPostProcessor`가 키 하나에서 파생해 둔 프로퍼티다 —
 * 판정이 두 벌이 되지 않는 것이 요점이며, 그 파생 자체는 그 클래스의 테스트가 본다.
 */
class AssistantConfigTest {

    /*
     * ⚠️ **변환 서비스를 손으로 붙인다.** `SpringApplication`이 부팅 때 등록하는
     * `ApplicationConversionService`가 여기에는 없어서 `@Value("P7D")`가 `Duration`으로 바뀌지
     * 않는다 — 실제 부팅에서는 되는 일이 이 러너에서만 «변환할 수 없다»로 죽는다.
     */
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withInitializer(
                            context ->
                                    context.getBeanFactory()
                                            .setConversionService(
                                                    ApplicationConversionService
                                                            .getSharedInstance()))
                    .withUserConfiguration(AssistantConfig.class, StubModels.class);

    /* 키가 있는 환경 — 두 프로퍼티가 아예 없다(EPP가 아무것도 넣지 않았다) */
    @Test
    void wiresBothWhenTheModelsAreOn() {
        runner.run(
                context -> {
                    assertThat(context).hasSingleBean(ChatClient.class);
                    assertThat(context.getBean(RagChunkStore.class))
                            .isInstanceOf(PgVectorRagChunkStore.class);
                });
    }

    /*
     * **질의 임베딩 캐시가 임베딩 모델 자리를 차지한다** (#406 · 기획안 §7.1).
     *
     * 저장소 자동 구성이 `EmbeddingModel`을 **타입으로** 주입받으므로 «우선 빈이 우리 것»이라는
     * 사실이 곧 «캐시가 실제로 걸린다»이다 — 여기가 아니면 그 배선을 확인할 자리가 없다.
     */
    @Test
    void putsTheQueryEmbeddingCacheInFrontOfTheModel() {
        runner.run(
                context ->
                        assertThat(context.getBean(EmbeddingModel.class))
                                .isInstanceOf(QueryEmbeddingCache.class));
    }

    /*
     * **대화 메모리는 조건이 없다** — 담는 것이 우리가 만든 문자열 둘뿐이라 Gemini 키와 무관하다.
     *
     * 빈으로 두는 이유는 자동 구성(`ChatMemoryAutoConfiguration`)이 **기본 창 10턴**으로 세워
     * 두는 것을 물리기 위해서다. 그 기본 배선에는 대화 **개수**의 천장이 아예 없다(§7.3).
     */
    @Test
    void wiresTheConversationMemoryEvenWithoutAnyModel() {
        runner.withPropertyValues(
                        "spring.ai.model.chat=none", "spring.ai.model.embedding.text=none")
                .run(context -> assertThat(context).hasSingleBean(ChatMemory.class));
    }

    /* 키가 없는 환경 — EPP가 `none`을 넣어 두었다. 여기서 빈이 서면 H2 테스트가 통째로 죽는다 */
    @Test
    void wiresNeitherWhenTheModelsAreOff() {
        runner.withPropertyValues(
                        "spring.ai.model.chat=none", "spring.ai.model.embedding.text=none")
                .run(
                        context -> {
                            assertThat(context).doesNotHaveBean(ChatClient.class);
                            assertThat(context).doesNotHaveBean(RagChunkStore.class);
                            assertThat(context).doesNotHaveBean(QueryEmbeddingCache.class);
                        });
    }

    /* 두 스위치는 서로 다른 키에서 오므로 한쪽만 꺼질 수 있다 */
    @Test
    void eachBeanFollowsItsOwnSwitch() {
        runner.withPropertyValues("spring.ai.model.embedding.text=none")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(ChatClient.class);
                            assertThat(context).doesNotHaveBean(RagChunkStore.class);
                        });
    }

    /*
     * 스타터가 만드는 두 빈의 자리. 실제 구현은 Gemini·PostgreSQL 연결을 요구하므로 여기서
     * 확인하려는 것(조건이 맞는가)과 무관한 비용이 된다.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class StubModels {

        @Bean
        ChatModel chatModel() {
            return mock(ChatModel.class);
        }

        @Bean
        VectorStore vectorStore() {
            return mock(VectorStore.class);
        }

        /** 질의 임베딩 캐시가 감싸는 대상. 실제 구현은 Gemini 연결을 요구한다 */
        @Bean
        GoogleGenAiTextEmbeddingModel googleGenAiTextEmbedding() {
            return mock(GoogleGenAiTextEmbeddingModel.class);
        }

        /** 대화 메모리 빈이 받는 저장소와 시계. 둘 다 우리 것이라 진짜를 쓴다 */
        @Bean
        AssistantMemoryStore assistantMemoryStore(Clock clock) {
            return new AssistantMemoryStore(500, Duration.ofHours(24), clock);
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }
}
