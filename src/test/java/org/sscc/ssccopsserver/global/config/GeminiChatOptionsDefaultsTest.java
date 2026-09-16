package org.sscc.ssccopsserver.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

/*
 * `application.yaml`이 선언한 채팅 기본값이 실제로 쓸 수 있는 값인가 (#453).
 *
 * ══ 왜 이 테스트가 필요한가 ════════════════════════════════════
 *
 * **아무것도 이 두 줄을 검증하지 않는다.** 키가 없으면 `GeminiWiringEnvironmentPostProcessor`가
 * 채팅 자동 구성을 통째로 끄므로(#396) 이 프로퍼티는 **바인딩조차 되지 않는다** — 그리고 CI와
 * 기여자 로컬은 언제나 키가 없는 환경이다. 오타 하나가 초록 불로 머지되어 **dev 배포에서 처음
 * 드러난다**(머지가 곧 dev 배포다 · 루트 AGENTS.md).
 *
 * 그래서 부팅이 아니라 **선언 자체**를 읽어 본다. `@SpringBootTest`가 아닌 것은 컨텍스트를 하나
 * 더 세워도 어차피 그 컨텍스트에서는 이 값이 바인딩되지 않기 때문이다.
 */
class GeminiChatOptionsDefaultsTest {

    /** `${VAR:기본값}` 에서 기본값 — 환경변수가 비어 있을 때 실제로 쓰이는 값이다 */
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{[^:}]+:(.*)}$");

    /** 이 접두사를 가진 모델에서는 MINIMAL·MEDIUM 이 거부된다 (`validateThinkingLevelForModel`) */
    private static final String RESTRICTED_MODEL = "gemini-3-pro";

    @Test
    void theDeclaredThinkingLevelIsARealOne() {
        assertThat(GoogleGenAiThinkingLevel.valueOf(defaultOf("thinking-level")))
                .as("application.yaml 의 thinking-level 이 GoogleGenAiThinkingLevel 에 없는 값이다")
                .isNotNull();
    }

    /*
     * **모델과 사고 수준은 서로를 제약한다.** Spring AI 의 `validateThinkingLevelForModel`이
     * Gemini 3 Pro 계열에서 LOW·HIGH 밖의 값을 거부하므로, 모델만 Pro 로 바꾸면 **부팅이
     * 깨진다.** 두 줄이 떨어져 있어 한쪽만 고치기 쉬운 자리라 여기서 묶어 둔다.
     */
    @Test
    void theThinkingLevelIsLegalForTheModelItShipsWith() {
        String model = defaultOf("model").toLowerCase(Locale.ROOT);
        GoogleGenAiThinkingLevel level =
                GoogleGenAiThinkingLevel.valueOf(defaultOf("thinking-level"));

        if (model.startsWith(RESTRICTED_MODEL)) {
            assertThat(level)
                    .as("%s 는 LOW·HIGH 만 받는다 — 모델을 바꿨으면 thinking-level 도 함께 본다", model)
                    .isIn(GoogleGenAiThinkingLevel.LOW, GoogleGenAiThinkingLevel.HIGH);
        }
    }

    @SuppressWarnings("unchecked")
    private static String defaultOf(String key) {
        try (InputStream yaml = new ClassPathResource("application.yaml").getInputStream()) {
            Map<String, Object> at = new Yaml().load(yaml);
            for (String step :
                    new String[] {"spring", "ai", "google", "genai", "chat", "options"}) {
                at = (Map<String, Object>) at.get(step);
            }

            String declared = String.valueOf(at.get(key));
            Matcher placeholder = PLACEHOLDER.matcher(declared);
            assertThat(placeholder.matches())
                    .as("%s 는 ${환경변수:기본값} 모양이어야 한다 — 값을 코드에 굳히지 않는다", key)
                    .isTrue();
            return placeholder.group(1);

        } catch (Exception failure) {
            throw new IllegalStateException(
                    "application.yaml 의 %s 를 읽지 못했다".formatted(key), failure);
        }
    }
}
