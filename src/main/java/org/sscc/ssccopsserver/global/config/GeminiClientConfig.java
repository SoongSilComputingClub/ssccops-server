package org.sscc.ssccopsserver.global.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;

import lombok.extern.slf4j.Slf4j;

/*
 * Gemini 호출에 **시간 상한**을 건다 (#403 · 기획안 §10 · §14.2).
 *
 * ══ 왜 스타터에 맡기지 않는가 — 실측 ════════════════════════════
 *
 * **google-genai SDK의 기본값은 «무한»이다.** 1.37.0의 `ApiClient.createHttpClient`가 OkHttp에
 * `connectTimeout(Duration.ofMillis(0))`을 걸고 read·write도 같다 — OkHttp에서 0은 「제한
 * 없음」이다. 그 상태에서 공급자가 응답하지 않으면 **질의 한 건이 톰캣 요청 스레드를 영영
 * 붙든다.** 스타터의 프로퍼티에는 타임아웃이 없다(1.1.8의 `spring-configuration-metadata.json`
 * 을 확인했다 — `spring.ai.google.genai.*`에 그런 키가 없다).
 *
 * 그래서 스타터가 만드는 `Client` 빈을 대신 만든다. 자동 구성의 그 빈이
 * `@ConditionalOnMissingBean`이라 **허용된 길**이며(MCP 전송 빈을 직접 만드는 것과 같은 자리),
 * 값은 `HttpOptions.timeout(ms)` 하나다 — SDK가 그것을 OkHttp의 `callTimeout`으로 건다.
 *
 * ⚠️ **connect와 read를 따로 줄 수 없다.** 기획안이 적은 «connect 3s / read 20s»는 SDK가 받지
 * 않는 모양이라 **전체 왕복 하나**로 합쳤다. 목표(p95 < 5s · §14.2)를 재는 자리는 골든셋(#405)
 * 이고, 값이 좁으면 긴 답변이 잘리는 것이 아니라 통째로 503이 된다.
 *
 * ══ 임베딩은 이 빈을 쓰지 않는다 ═══════════════════════════════
 *
 * 임베딩 스타터는 `GoogleGenAiEmbeddingConnectionDetails`로 따로 연결한다 — 그래서 이 상한은
 * **질의 경로에만** 걸린다. 색인 워커가 임베딩을 수백 번 부르는 자리(#400)에 20초 상한을 거는
 * 것은 그쪽의 성질(느려도 끝나기만 하면 된다)과 맞지 않으므로 마침 맞는 분리다.
 *
 * ══ 조건 ═══════════════════════════════════════════════════════
 *
 * `AssistantConfig`의 채팅 클라이언트와 **같은 스위치**를 본다 — 키가 없으면 그쪽 자동 구성이
 * 통째로 꺼지므로(`GeminiWiringEnvironmentPostProcessor`) 여기서만 살아 있는 `Client` 빈은
 * 아무도 쓰지 않는 채 키 없이 만들어지려다 실패할 뿐이다. 판정이 두 벌이 되지 않도록 같은
 * 표현을 쓴다.
 */
@Slf4j
@Configuration
public class GeminiClientConfig {

    /*
     * 전체 왕복 상한. 기본 20초는 기획안의 read 타임아웃 값이며, 화면이 «일시적으로 답할 수
     * 없어요»(§13.1)를 그리기까지 사용자가 기다리는 최대 시간이기도 하다.
     */
    @Bean
    @ConditionalOnExpression("'${spring.ai.model.chat:}' != 'none'")
    Client googleGenAiClient(
            @Value("${spring.ai.google.genai.api-key:}") String apiKey,
            @Value("${ssccops.assistant.gemini.call-timeout}") Duration callTimeout) {

        log.info("Gemini 채팅 클라이언트 — 호출 상한 {}ms", callTimeout.toMillis());
        return Client.builder()
                .apiKey(apiKey)
                .httpOptions(
                        HttpOptions.builder()
                                .timeout(Math.toIntExact(callTimeout.toMillis()))
                                .build())
                .build();
    }
}
