package org.sscc.ssccopsserver.global.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiEmbeddingConnectionAutoConfiguration;
import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

/*
 * Gemini API 키가 없으면 규정 도우미(RAG) 배선을 끈다 (#395 · #396).
 *
 * ── 왜 코드가 필요한가 ─────────────────────────────────────────
 *
 * 두 스타터(google-genai · google-genai-embedding)의 자동 구성은 **키가 없으면 부팅을
 * 실패시킨다.** 그것도 프로퍼티로는 끌 수 없는 자리가 둘 있다:
 *
 *   - `GoogleGenAiChatAutoConfiguration`      `spring.ai.model.chat` 조건이 붙어 있다
 *                                             (matchIfMissing=true → 기본으로 켜진다).
 *                                             키·project-id 가 둘 다 없으면
 *                                             IllegalStateException("Incomplete Google GenAI
 *                                             configuration…") 으로 빈 생성이 깨진다.
 *   - `GoogleGenAiTextEmbeddingAutoConfiguration`
 *                                             `spring.ai.model.embedding.text` 조건이 붙어 있다.
 *   - `GoogleGenAiEmbeddingConnectionAutoConfiguration`
 *                                             **조건이 아예 없다.** 키가 비면 Vertex AI 경로로
 *                                             흘러 Assert.hasText(projectId, "Google GenAI
 *                                             project-id must be set!") 에서 죽는다. 위 두
 *                                             프로퍼티를 `none` 으로 둬도 이 빈은 그대로 만들어진다.
 *   - `PgVectorStoreAutoConfiguration` (#396)  **EmbeddingModel 빈을 생성자로 요구한다.** 키가
 *                                             없으면 그 빈이 없으므로 VectorStore 생성이
 *                                             NoSuchBeanDefinitionException 으로 깨진다. 조건은
 *                                             `spring.ai.vectorstore.type` 하나뿐이라 임베딩이
 *                                             꺼진 것을 알지 못한다 — 같은 사실에서 함께 끈다.
 *                                             (덤으로 H2 테스트가 실제 PostgreSQL 없이 뜬다.)
 *
 * 그래서 이 넷을 **한 가지 사실**(키가 있는가)에서 함께 끈다. 프로퍼티 세 줄을 설정 파일에
 * 적어 두는 방법도 있지만 그러면 켤 때 그 줄들을 함께 되돌려야 하고, 한 줄만 빠뜨린 상태가
 * 「키는 넣었는데 임베딩만 안 되는」 조용한 고장이 된다 — 같은 사실이 두 벌이 되면 한쪽만
 * 바뀐다는, 이 저장소가 반복해서 데인 자리다.
 *
 * ── 왜 「키를 요구한다」가 아닌가 ────────────────────────────────
 *
 * `AppPublicBaseUrl`(#216)은 값이 없으면 부팅을 세운다. 그 근거는 「비어 있을 정당한 이유가
 * 어느 환경에도 없다」였는데 **이 값에는 그 근거가 성립하지 않는다.** 규정 도우미는 기능
 * 플래그 뒤에 있고(#396), 키를 발급받지 않은 기여자 로컬·테스트·아직 키를 넣지 않은 배포가
 * 전부 정당한 상태다. 이 값이 없는 서버는 설정이 덜 된 서버가 아니라 「규정 도우미가 없는
 * 서버」다. 대신 **조용히 끄지는 않는다** — 부팅 로그에 한 줄을 남긴다.
 *
 * 테스트 프로필에 더미 키를 넣는 방법(r2.* 가 그렇게 한다)은 택하지 않았다. R2 는 어느
 * 테스트에서도 실제로 호출되지 않지만, 더미 키를 넣으면 **임베딩 빈이 살아 있는 채로** 떠
 * 나중에 누군가 그것을 부르는 코드를 쓰는 순간 401 을 받는다 — 그 실패는 「키가 없다」가 아니라
 * 「Gemini 가 거절했다」로 보여 원인이 한 겹 멀어진다.
 *
 * ── 순서 ─────────────────────────────────────────────────────
 *
 * `ConfigDataEnvironmentPostProcessor` 보다 **뒤에** 돌아야 application-*.yaml 이 읽은
 * api-key 를 볼 수 있다. 우리가 넣는 프로퍼티 소스는 맨 앞에 놓이지만 **이미 명시된 값은
 * 건드리지 않는다** — 키 없이도 굳이 켜 보겠다는 설정이 있으면 그쪽이 이긴다(그리고 부팅이
 * 실패한다. 그것이 명시적으로 요청한 결과다).
 */
public class GeminiWiringEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** 이 클래스가 넣는 프로퍼티 소스의 이름 — 테스트와 /actuator/env 에서 출처가 드러나야 한다 */
    static final String PROPERTY_SOURCE_NAME = "ssccops-gemini-wiring";

    static final String CHAT_API_KEY = "spring.ai.google.genai.api-key";
    static final String EMBEDDING_API_KEY = "spring.ai.google.genai.embedding.api-key";

    /** 값이 `none` 이면 해당 자동 구성이 통째로 비활성화된다 (스타터의 @ConditionalOnProperty) */
    static final String CHAT_MODEL_SELECTOR = "spring.ai.model.chat";

    static final String EMBEDDING_MODEL_SELECTOR = "spring.ai.model.embedding.text";

    static final String AUTOCONFIGURE_EXCLUDE = "spring.autoconfigure.exclude";

    private static final String NONE = "none";

    /*
     * 임베딩 키가 없으면 함께 꺼야 하는 자동 구성. 둘 다 프로퍼티로는 끌 수 없다 — 앞의 것은
     * 조건이 아예 없고, 뒤의 것(#396)은 `spring.ai.vectorstore.type`만 보므로 임베딩이 꺼진 것을
     * 알지 못한 채 EmbeddingModel 을 요구한다.
     */
    private static final List<Class<?>> EMBEDDING_DEPENDENT_AUTO_CONFIGURATIONS =
            List.of(
                    GoogleGenAiEmbeddingConnectionAutoConfiguration.class,
                    PgVectorStoreAutoConfiguration.class);

    private final Log log;

    public GeminiWiringEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(GeminiWiringEnvironmentPostProcessor.class);
    }

    @Override
    public int getOrder() {
        // application-*.yaml 이 읽힌 **뒤에** 돌아야 거기 적힌 api-key 를 볼 수 있다
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    @Override
    public void postProcessEnvironment(
            ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> overrides = new LinkedHashMap<>();

        if (!hasText(environment, CHAT_API_KEY)
                && !environment.containsProperty(CHAT_MODEL_SELECTOR)) {
            overrides.put(CHAT_MODEL_SELECTOR, NONE);
        }

        if (!hasText(environment, EMBEDDING_API_KEY)) {
            if (!environment.containsProperty(EMBEDDING_MODEL_SELECTOR)) {
                overrides.put(EMBEDDING_MODEL_SELECTOR, NONE);
            }
            /*
             * 커넥션·pgvector 자동 구성은 위의 selector 만으로 꺼지지 않는다 — 제외 목록에
             * 더하는 것이 유일한 방법이다. 이미 적힌 값이 있으면 덮어쓰지 않고 **합친다**.
             */
            overrides.put(AUTOCONFIGURE_EXCLUDE, mergedExcludes(environment));
        }

        if (overrides.isEmpty()) {
            return;
        }

        environment
                .getPropertySources()
                .addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, overrides));
        log.info(
                "Gemini API 키가 없어 규정 도우미(RAG) 모델 배선을 끕니다 — "
                        + overrides.keySet()
                        + ". 켜려면 GEMINI_API_KEY(dev·prod 는 DEV_/PROD_ 접두)를 넣으세요.");
    }

    private boolean hasText(ConfigurableEnvironment environment, String key) {
        return StringUtils.hasText(environment.getProperty(key));
    }

    /**
     * 기존 {@code spring.autoconfigure.exclude} 에 임베딩에 매인 자동 구성들을 더한 쉼표 목록.
     *
     * <p>목록 형태(`- a`)로 적혀 있으면 {@code getProperty} 로는 보이지 않으므로 {@link Binder} 로 읽는다. 우리 소스가 맨 앞이라
     * 여기서 돌려주는 값이 그대로 최종 목록이 된다 — 기존 값을 빠뜨리면 남이 제외해 둔 자동 구성이 되살아난다.
     */
    private String mergedExcludes(ConfigurableEnvironment environment) {
        List<String> excludes =
                new ArrayList<>(
                        Binder.get(environment)
                                .bind(AUTOCONFIGURE_EXCLUDE, Bindable.listOf(String.class))
                                .orElseGet(List::of));

        for (Class<?> autoConfiguration : EMBEDDING_DEPENDENT_AUTO_CONFIGURATIONS) {
            String name = autoConfiguration.getName();
            if (!excludes.contains(name)) {
                excludes.add(name);
            }
        }
        return String.join(",", excludes);
    }
}
