package org.sscc.ssccopsserver.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.sscc.ssccopsserver.domain.assistant.service.PgVectorRagChunkStore;
import org.sscc.ssccopsserver.domain.assistant.service.RagChunkStore;

/*
 * 두 빈이 «모델이 있을 때만» 서는지 (#396).
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

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
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

    /* 키가 없는 환경 — EPP가 `none`을 넣어 두었다. 여기서 빈이 서면 H2 테스트가 통째로 죽는다 */
    @Test
    void wiresNeitherWhenTheModelsAreOff() {
        runner.withPropertyValues(
                        "spring.ai.model.chat=none", "spring.ai.model.embedding.text=none")
                .run(
                        context -> {
                            assertThat(context).doesNotHaveBean(ChatClient.class);
                            assertThat(context).doesNotHaveBean(RagChunkStore.class);
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
    }
}
