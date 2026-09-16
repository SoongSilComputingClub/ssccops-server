package org.sscc.ssccopsserver.global.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.retry.autoconfigure.SpringAiRetryProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;

import lombok.extern.slf4j.Slf4j;

/*
 * Gemini 호출의 **시간 상한과 재시도** (#403 · #448 · 기획안 §10 · §14.2).
 *
 * ══ 왜 스타터에 맡기지 않는가 — 실측 ════════════════════════════
 *
 * **google-genai SDK의 기본값은 「무한」이다.** 1.37.0의 `ApiClient.createHttpClient`가 OkHttp에
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
 * 않는 모양이라 **전체 왕복 하나**로 합쳤다. 그 하나에는 **본문을 다 읽는 시간까지** 들어가므로
 * 스트리밍(#447)도 같은 상한에 걸린다 — 실측했다: 상한 1,500ms에 흘려보내는 응답을 물리면
 * **조각 넷이 나간 뒤 1,863ms에 끊긴다.** 그때 실패의 모양은 통째로 503이 아니라 «글자가 나오다
 * 끊기고 오류 이벤트가 오는 것»이다.
 *
 * ══ 재시도의 정본이 여기인 이유 — 그것도 실측 (#448) ════════════
 *
 * **`spring.ai.retry`는 이 경로에 닿지 않는다.** `SpringAiRetryAutoConfiguration`이 만드는
 * `RetryTemplate`은 세 예외만 다시 부르는 **화이트리스트**이고(`TransientAiException` ·
 * `ResourceAccessException` · `WebClientRequestException`) google-genai가 던지는 것은 그 셋 중
 * 무엇도 아니다(`GenAiIOException` · `ApiException`). 재 봤다 — 그 템플릿에 `GenAiIOException`을
 * 던지면 콜백이 **한 번** 불리고 `TransientAiException`을 던지면 두 번 불린다. 게다가 스트리밍
 * 경로(`internalStream`)는 템플릿을 아예 지나지 않는다.
 *
 * **대신 SDK가 자기 재시도를 감춰 두고 있다.** `ApiClient.createHttpClient`가 `retryOptions`가
 * 비면 **기본값으로 `RetryInterceptor`를 끼운다**: 5회 · 1s에서 2배씩(지터 ±100%) ·
 * 408·429·5xx, 그리고 **IOException이면 상태 코드와 무관하게 다시 부른다.** 그것이 OkHttp의
 * **애플리케이션 인터셉터**라 `callTimeout`이 이미 터진 뒤에도 잠들고 또 잠든다 — 취소된 호출의
 * 재요청은 서버에 닿지도 않으므로 **잠들기만 한다.**
 *
 * 그래서 #448의 증상이 나왔다: 상한 20초인데 사용자가 **35~43초**를 기다린 끝에 503을 받는다.
 * 같은 모양을 작게 재현하면 이렇다(상한 1초 · 응답하지 않는 서버 · 서버가 받은 요청은 1건).
 *
 * | `retryOptions` | 사용자가 기다린 시간 |
 * |---|---|
 * | 없음(= SDK 기본 5회) | **18,200ms** |
 * | `attempts=2` | 2,312ms |
 * | `attempts=1` | 1,021ms |
 *
 * **재시도를 없애지 않고 좁히는 것은 그 재시도가 쓸모 있을 때가 있기 때문이다** — 예산이 남아
 * 있으면 재요청은 실제로 서버에 닿는다(상한 5초 · 503을 내는 서버 · 요청 2건 · 388ms). 잃는
 * 것은 「이미 시간이 다 된 호출을 한 번 더 기다리는 일」뿐이다.
 *
 * **값은 `spring.ai.retry`에서 온다.** 같은 뜻의 손잡이를 두 벌 두지 않기 위해서이고, 그래서
 * 그 블록은 이제 장식이 아니라 실제로 무언가를 정한다. `on-client-errors`(기본 false)가 408·429를
 * 가른다 — 쿼터로 거절당한 요청을 다시 부르는 것은 쿼터 소진을 가속할 뿐이다(#400과 같은 근거).
 *
 * ⚠️ **IOException 재시도는 끌 수 없다.** 인터셉터의 `catch (IOException)`은 상태 코드 목록을
 * 보지 않는다 — `attempts`가 그 횟수까지 함께 정하는 유일한 손잡이다.
 *
 * ══ 임베딩은 이 빈을 쓰지 않는다 ═══════════════════════════════
 *
 * 임베딩 스타터는 `GoogleGenAiEmbeddingConnectionDetails`로 따로 연결한다 — 그래서 이 상한도,
 * 위의 재시도도 **질의 경로에만** 걸린다. 색인 워커가 임베딩을 수백 번 부르는 자리(#400)에
 * 왕복 상한을 거는 것은 그쪽의 성질(느려도 끝나기만 하면 된다)과 맞지 않으므로 마침 맞는
 * 분리이지만, **그쪽은 SDK 기본 재시도(429 포함 5회)를 그대로 쓴다**는 뜻이기도 하다.
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

    /** 공급자가 잠깐 힘든 것 — 다시 물으면 다른 답이 올 수 있다 */
    private static final List<Integer> SERVER_ERRORS = List.of(500, 502, 503, 504);

    /**
     * 요청 쪽 사정 — 408 요청 시간 초과 · 429 쿼터.
     *
     * <p><b>{@code spring.ai.retry.on-client-errors}가 true일 때만 더한다.</b> 기본은 false이고, 그것이 «쿼터로 거절당한
     * 요청을 다시 부르지 않는다»는 뜻이다.
     */
    private static final List<Integer> CLIENT_ERRORS = List.of(408, 429);

    @Bean
    @ConditionalOnExpression("'${spring.ai.model.chat:}' != 'none'")
    Client googleGenAiClient(
            @Value("${spring.ai.google.genai.api-key:}") String apiKey,
            @Value("${ssccops.assistant.gemini.call-timeout}") Duration callTimeout,
            SpringAiRetryProperties retry) {

        HttpOptions options = httpOptions(callTimeout, retry);
        log.info(
                "Gemini 채팅 클라이언트 — 호출 상한 {}ms · 재시도 {}회(상태 {} · {}초부터 ×{})",
                callTimeout.toMillis(),
                retry.getMaxAttempts(),
                options.retryOptions().flatMap(HttpRetryOptions::httpStatusCodes).orElseThrow(),
                seconds(retry.getBackoff().getInitialInterval()),
                retry.getBackoff().getMultiplier());

        return Client.builder().apiKey(apiKey).httpOptions(options).build();
    }

    /**
     * 전체 왕복 상한과 재시도 — <b>부르는 쪽에서 되읽을 수 없는 값들이라 여기서 조립하고 여기서 본다.</b>
     *
     * <p>SDK가 이 둘을 OkHttp의 {@code callTimeout}과 인터셉터로 감춰 두어, 만들어진 {@code Client}에서는 무엇이 걸렸는지 알 수
     * 없다. 그래서 테스트가 보는 것이 이 메서드의 반환값이다.
     */
    static HttpOptions httpOptions(Duration callTimeout, SpringAiRetryProperties retry) {
        return HttpOptions.builder()
                .timeout(Math.toIntExact(callTimeout.toMillis()))
                .retryOptions(retryOptions(retry))
                .build();
    }

    /**
     * {@code spring.ai.retry} → SDK의 {@code HttpRetryOptions}.
     *
     * <p><b>지터는 SDK 기본값(±100%)을 그대로 둔다.</b> 그래서 지연 한 번은 {@code [0, 2×initial-interval]}이고, 상한이 터진 뒤
     * 헛되이 기다리는 시간의 최악값도 그 값이다 — 상한 40초 · 초기 지연 1초라면 42초다.
     */
    private static HttpRetryOptions retryOptions(SpringAiRetryProperties retry) {
        List<Integer> statuses = new ArrayList<>(SERVER_ERRORS);
        if (retry.isOnClientErrors()) {
            statuses.addAll(CLIENT_ERRORS);
        }
        return HttpRetryOptions.builder()
                .attempts(retry.getMaxAttempts())
                .initialDelay(seconds(retry.getBackoff().getInitialInterval()))
                .maxDelay(seconds(retry.getBackoff().getMaxInterval()))
                .expBase((double) retry.getBackoff().getMultiplier())
                .httpStatusCodes(statuses)
                .build();
    }

    /** SDK는 지연을 «초(실수)»로 받고 스프링 쪽은 {@code Duration}이다 */
    private static double seconds(Duration duration) {
        return duration.toMillis() / 1000.0;
    }
}
