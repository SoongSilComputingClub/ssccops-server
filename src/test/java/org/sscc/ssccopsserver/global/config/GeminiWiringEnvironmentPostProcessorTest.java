package org.sscc.ssccopsserver.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.sscc.ssccopsserver.global.config.GeminiWiringEnvironmentPostProcessor.AUTOCONFIGURE_EXCLUDE;
import static org.sscc.ssccopsserver.global.config.GeminiWiringEnvironmentPostProcessor.CHAT_API_KEY;
import static org.sscc.ssccopsserver.global.config.GeminiWiringEnvironmentPostProcessor.CHAT_MODEL_SELECTOR;
import static org.sscc.ssccopsserver.global.config.GeminiWiringEnvironmentPostProcessor.EMBEDDING_API_KEY;
import static org.sscc.ssccopsserver.global.config.GeminiWiringEnvironmentPostProcessor.EMBEDDING_MODEL_SELECTOR;
import static org.sscc.ssccopsserver.global.config.GeminiWiringEnvironmentPostProcessor.PROPERTY_SOURCE_NAME;

import java.util.List;
import java.util.Map;

import org.apache.commons.logging.impl.NoOpLog;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiEmbeddingConnectionAutoConfiguration;
import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.core.io.support.SpringFactoriesLoader.ArgumentResolver;

/*
 * Gemini 키가 없을 때 배선이 꺼지는지 (#395).
 *
 * **이 클래스가 없으면 확인할 자리가 없다.** 꺼지지 않으면 드러나는 방식이 "부팅 실패"라
 * 언뜻 안전해 보이지만, 실제로 죽는 곳은 규정 도우미와 아무 상관 없는 환경들이다 — 키를
 * 발급받지 않은 기여자 로컬 · 테스트 · 아직 키를 넣지 않은 dev 배포(머지가 곧 dev 배포다).
 *
 * 자동 구성 넷 중 둘은 프로퍼티로 끌 수 없어 제외 목록에 넣는 것이 유일한 방법이다 —
 * `GoogleGenAiEmbeddingConnectionAutoConfiguration`(조건이 아예 없다)과
 * `PgVectorStoreAutoConfiguration`(#396 · EmbeddingModel 빈을 생성자로 요구하는데 그 조건을
 * 보지 않는다). 그래서 이 테스트는 「selector 두 개」가 아니라 **넷 다**를 본다.
 */
class GeminiWiringEnvironmentPostProcessorTest {

    private static final DeferredLogFactory NO_OP_LOGS = destination -> new NoOpLog();

    private static final String CONNECTION_AUTO_CONFIGURATION =
            GoogleGenAiEmbeddingConnectionAutoConfiguration.class.getName();

    private static final String PGVECTOR_AUTO_CONFIGURATION =
            PgVectorStoreAutoConfiguration.class.getName();

    /* 키가 없는 환경 — 테스트·기여자 로컬·아직 키를 넣지 않은 배포가 전부 여기다 */
    @Test
    void withoutApiKeyTurnsChatAndEmbeddingOff() {
        StandardEnvironment environment =
                environmentWith(Map.of(CHAT_API_KEY, "", EMBEDDING_API_KEY, ""));

        postProcess(environment);

        assertThat(environment.getProperty(CHAT_MODEL_SELECTOR)).isEqualTo("none");
        assertThat(environment.getProperty(EMBEDDING_MODEL_SELECTOR)).isEqualTo("none");
        assertThat(environment.getProperty(AUTOCONFIGURE_EXCLUDE))
                .contains(CONNECTION_AUTO_CONFIGURATION)
                .contains(PGVECTOR_AUTO_CONFIGURATION);
    }

    /* 키가 있으면 아무것도 건드리지 않는다 — 스타터가 설계대로 돈다 */
    @Test
    void withApiKeyLeavesEverythingAlone() {
        StandardEnvironment environment =
                environmentWith(Map.of(CHAT_API_KEY, "AIza-test", EMBEDDING_API_KEY, "AIza-test"));

        postProcess(environment);

        assertThat(environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)).isFalse();
        assertThat(environment.getProperty(CHAT_MODEL_SELECTOR)).isNull();
        assertThat(environment.getProperty(EMBEDDING_MODEL_SELECTOR)).isNull();
        assertThat(environment.getProperty(AUTOCONFIGURE_EXCLUDE)).isNull();
    }

    /*
     * 두 키는 서로 다른 커넥션 프로퍼티다. 한쪽만 비어 있으면 그쪽만 꺼야 한다 —
     * 임베딩 키가 없다고 채팅까지 끄면 "키는 넣었는데 아무것도 안 된다"가 된다.
     */
    @Test
    void eachSideSwitchesOnItsOwnKey() {
        StandardEnvironment environment =
                environmentWith(Map.of(CHAT_API_KEY, "AIza-test", EMBEDDING_API_KEY, ""));

        postProcess(environment);

        assertThat(environment.getProperty(CHAT_MODEL_SELECTOR)).isNull();
        assertThat(environment.getProperty(EMBEDDING_MODEL_SELECTOR)).isEqualTo("none");
        assertThat(environment.getProperty(AUTOCONFIGURE_EXCLUDE))
                .contains(CONNECTION_AUTO_CONFIGURATION)
                .contains(PGVECTOR_AUTO_CONFIGURATION);
    }

    /*
     * 남이 제외해 둔 자동 구성을 되살리지 않는다. 우리 소스가 맨 앞이라 여기서 빠뜨린 값은
     * 그대로 사라진다 — 덮어쓰는 것이 아니라 합쳐야 한다.
     */
    @Test
    void keepsExistingAutoConfigurationExcludes() {
        StandardEnvironment environment =
                environmentWith(
                        Map.of(
                                EMBEDDING_API_KEY, "",
                                AUTOCONFIGURE_EXCLUDE,
                                        "com.example.SomeoneElsesAutoConfiguration"));

        postProcess(environment);

        assertThat(environment.getProperty(AUTOCONFIGURE_EXCLUDE))
                .contains("com.example.SomeoneElsesAutoConfiguration")
                .contains(CONNECTION_AUTO_CONFIGURATION)
                .contains(PGVECTOR_AUTO_CONFIGURATION);
    }

    /*
     * 키 없이도 굳이 켜 보겠다고 명시한 설정은 그쪽이 이긴다(그리고 부팅이 실패한다 —
     * 명시적으로 요청한 결과다). 우리가 넣는 것은 «정하지 않았을 때의 기본값»이다.
     */
    @Test
    void doesNotOverrideAnExplicitSelector() {
        StandardEnvironment environment =
                environmentWith(
                        Map.of(
                                CHAT_API_KEY, "",
                                CHAT_MODEL_SELECTOR, "google-genai"));

        postProcess(environment);

        assertThat(environment.getProperty(CHAT_MODEL_SELECTOR)).isEqualTo("google-genai");
    }

    /*
     * 등록 자체가 살아 있는가. EnvironmentPostProcessor 는 자동 구성이 아니라
     * META-INF/spring.factories 로 등록되므로, 오타 난 FQN·지워진 줄은 **아무 소리 없이**
     * 이 클래스를 무효로 만든다 — 스프링이 부르지 않을 뿐 컴파일도 테스트도 통과한다.
     */
    @Test
    void isRegisteredInSpringFactories() {
        List<EnvironmentPostProcessor> processors =
                SpringFactoriesLoader.forDefaultResourceLocation()
                        .load(
                                EnvironmentPostProcessor.class,
                                ArgumentResolver.of(DeferredLogFactory.class, NO_OP_LOGS));

        assertThat(processors)
                .hasAtLeastOneElementOfType(GeminiWiringEnvironmentPostProcessor.class);
    }

    private static StandardEnvironment environmentWith(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource("test", properties));
        return environment;
    }

    private static void postProcess(StandardEnvironment environment) {
        new GeminiWiringEnvironmentPostProcessor(NO_OP_LOGS)
                .postProcessEnvironment(environment, null);
    }
}
