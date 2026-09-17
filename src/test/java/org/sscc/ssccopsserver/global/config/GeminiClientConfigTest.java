package org.sscc.ssccopsserver.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryProperties;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.google.genai.Client;
import com.google.genai.errors.GenAiIOException;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.sun.net.httpserver.HttpServer;

/*
 * Gemini 호출의 상한과 재시도 (#403 · #448).
 *
 * ══ 무엇을 지키는 자리인가 ═════════════════════════════════════
 *
 * ① **빈이 키가 있을 때만 선다** — SDK 기본값이 「무한」이라 이 빈이 상한의 전부인데, 키가 없는
 *    환경(테스트 · 기여자 로컬 · 아직 키를 넣지 않은 배포)에서 서면 키 없는 클라이언트를
 *    만들려다 부팅이 깨진다.
 * ② **재시도가 좁혀져 있다** — #448의 증상(상한 20초인데 사용자는 35~43초를 기다린다)이 SDK가
 *    감춰 둔 기본 재시도(5회)에서 왔다. 그것을 되돌리면 이 테스트가 먼저 떨어진다.
 *
 * ⚠️ **만들어진 `Client`에서는 무엇이 걸렸는지 되읽을 수 없다.** SDK가 상한을 OkHttp의
 * `callTimeout`으로, 재시도를 인터셉터로 감춰 둔다 — 그래서 값 쪽은 `GeminiClientConfig`가
 * 조립한 `HttpOptions`를 보고, **정말 그렇게 도는가**는 진짜 SDK 클라이언트를 가짜 서버에
 * 물려서 본다(`McpRestClientTest`가 JDK HttpServer로 REST를 흉내 내는 것과 같은 자리).
 */
class GeminiClientConfigTest {

    /*
     * 변환 서비스를 손으로 붙인다 — 문자열 → `Duration` 변환은 `SpringApplication`이 등록하는
     * `ApplicationConversionService`가 해 주는데 이 러너에는 그것이 없다. 실제 부팅에는 있으므로
     * 이것은 테스트 하네스의 차이이지 설정의 제약이 아니다(`ssccops.assistant.indexing.poll-interval`
     * 도 같은 모양이다).
     *
     * ⚠️ **상한 값도 손으로 준다** (#439). 기본값이 `application.yaml`로 옮겨 가며 `@Value`에서
     * 빠졌는데 이 러너는 그 파일을 읽지 않는다 — 같은 하네스 차이다. 운영 기본값(PT40S)과 다른
     * 값을 주는 것은 이 테스트가 보는 것이 **조건**이지 값이 아니기 때문이며, 같은 숫자를 적으면
     * 기본값이 여기에도 한 벌 생긴다.
     *
     * 재시도 프로퍼티는 자동 구성째 들여온다 — `spring.ai.retry.*`가 실제로 바인딩되는 길이
     * 그것이고, 우리가 `SpringAiRetryProperties`를 손으로 만들면 «이 빈이 어디서 오는가»가
     * 테스트에만 있는 사실이 된다.
     */
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withInitializer(
                            context ->
                                    context.getBeanFactory()
                                            .setConversionService(
                                                    ApplicationConversionService
                                                            .getSharedInstance()))
                    .withConfiguration(AutoConfigurations.of(SpringAiRetryAutoConfiguration.class))
                    .withPropertyValues("ssccops.assistant.gemini.call-timeout=PT7S")
                    .withUserConfiguration(GeminiClientConfig.class);

    /* 키가 있는 환경 — `spring.ai.model.chat`이 아예 없다(EPP가 아무것도 넣지 않았다) */
    @Test
    void wiresTheClientWhenTheChatModelIsOn() {
        runner.withPropertyValues("spring.ai.google.genai.api-key=test-key")
                .run(context -> assertThat(context).hasSingleBean(Client.class));
    }

    /* 키가 없는 환경 — EPP가 `none`을 넣어 두었다. 여기서 빈이 서면 키 없이 만들어지려다 깨진다 */
    @Test
    void wiresNothingWhenTheChatModelIsOff() {
        runner.withPropertyValues("spring.ai.model.chat=none")
                .run(context -> assertThat(context).doesNotHaveBean(Client.class));
    }

    // ------------------------------------------------------------------ 값 (#448)

    /*
     * **`spring.ai.retry`가 SDK 재시도의 정본이다** — 같은 뜻의 손잡이를 두 벌 두지 않는다.
     *
     * 여기서 숫자가 그대로 옮겨지는지 보는 이유는, 옮겨 담는 코드가 없으면 그 블록이 **아무것도
     * 정하지 않는 장식**으로 되돌아가기 때문이다(아래 {@link #springAiRetryTemplateNeverRetriesWhatGeminiThrows()}).
     */
    @Test
    void carriesTheRetryPropertiesIntoTheSdksOwnRetry() {
        HttpOptions options = GeminiClientConfig.httpOptions(Duration.ofSeconds(40), retry(false));

        assertThat(options.timeout()).contains(40_000);
        HttpRetryOptions sdkRetry = options.retryOptions().orElseThrow();
        assertThat(sdkRetry.attempts()).contains(2);
        assertThat(sdkRetry.initialDelay()).contains(1.0);
        assertThat(sdkRetry.maxDelay()).contains(5.0);
        assertThat(sdkRetry.expBase()).contains(2.0);
    }

    /*
     * **`on-client-errors`가 408·429를 가른다** (기본 false).
     *
     * 쿼터로 거절당한 요청을 다시 부르는 것은 쿼터 소진을 가속할 뿐이다(#400과 같은 근거). 그
     * 프로퍼티의 이름이 말하는 바가 이 경로에서도 지켜지는지가 여기서 드러난다.
     */
    @Test
    void retriesClientErrorsOnlyWhenAskedTo() {
        assertThat(statusCodes(retry(false))).containsExactly(500, 502, 503, 504);
        assertThat(statusCodes(retry(true))).containsExactly(500, 502, 503, 504, 408, 429);
    }

    // ------------------------------------------------------------------ 정말 그렇게 도는가 (#448)

    /*
     * **응답하지 않는 서버에서 사용자가 기다리는 시간이 상한에 매여 있다.**
     *
     * #448의 증상이 여기 있었다: SDK 기본 재시도(5회)는 `callTimeout`이 터진 뒤에도 잠들고 또
     * 잠들어 — 취소된 호출의 재요청은 **서버에 닿지도 않는다** — 상한 1초를 **18,200ms**로
     * 부풀렸다. 좁힌 지금은 «상한 + 지연 한 번»이며 지연의 최악값이 초기 지연의 두 배다(지터).
     */
    @Test
    void doesNotKeepTheCallerWaitingLongPastTheTimeout() {
        try (FakeGemini gemini = FakeGemini.thatNeverAnswers()) {
            long startedAt = System.nanoTime();
            assertThatCallFails(gemini, Duration.ofSeconds(1));
            long elapsed = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

            assertThat(elapsed).as("상한 1초 + 지연 한 번(최악 2×0.2초)").isLessThan(2_000L);
            assertThat(gemini.requests()).as("취소된 호출의 재요청은 서버에 닿지 않는다").isEqualTo(1);
        }
    }

    /* **예산이 남아 있으면 재시도는 실제로 쓸모가 있다** — 5xx 한 번은 다시 물어본다 */
    @Test
    void retriesAServerErrorExactlyOnce() {
        try (FakeGemini gemini = FakeGemini.thatAlwaysReturns(503)) {
            assertThatCallFails(gemini, Duration.ofSeconds(5));

            assertThat(gemini.requests()).isEqualTo(2);
        }
    }

    /* **쿼터는 다시 부르지 않는다** — 429는 재시도 목록에 없다(`on-client-errors: false`) */
    @Test
    void neverRetriesAQuotaRejection() {
        try (FakeGemini gemini = FakeGemini.thatAlwaysReturns(429)) {
            assertThatCallFails(gemini, Duration.ofSeconds(5));

            assertThat(gemini.requests()).isEqualTo(1);
        }
    }

    /*
     * ⚠️ **`spring.ai.retry`의 `RetryTemplate`은 Gemini가 던지는 것을 다시 부르지 않는다.**
     *
     * 이 사실이 위의 모든 것의 이유다 — 그 템플릿은 세 예외만 다시 부르는 화이트리스트이고
     * (`TransientAiException` · `ResourceAccessException` · `WebClientRequestException`)
     * google-genai는 `GenAiIOException`·`ApiException`을 던진다. 그래서 재시도의 정본이
     * `GeminiClientConfig`이며, 이 테스트가 그 전제를 지킨다: 언젠가 스타터가 예외를
     * `TransientAiException`으로 감싸기 시작하면 **재시도가 두 겹이 되므로** 여기가 먼저 떨어져야
     * 한다(2 × 2 = 네 번 부르는 상태가 조용히 성립하지 않도록).
     */
    @Test
    void springAiRetryTemplateNeverRetriesWhatGeminiThrows() {
        assertThat(callsUntilGivingUp(() -> new GenAiIOException("timeout"))).isEqualTo(1);
        assertThat(callsUntilGivingUp(() -> new TransientAiException("공급자가 잠깐 힘들다")))
                .as("화이트리스트에 있는 예외는 다시 부른다 — 위 1회가 «재시도가 꺼져 있다»가 아니라는 증거")
                .isEqualTo(2);
    }

    // ------------------------------------------------------------------ 픽스처

    private static List<Integer> statusCodes(SpringAiRetryProperties properties) {
        return GeminiClientConfig.httpOptions(Duration.ofSeconds(40), properties)
                .retryOptions()
                .flatMap(HttpRetryOptions::httpStatusCodes)
                .orElseThrow();
    }

    /** `application.yaml`의 값 그대로 — 지연만 테스트가 기다릴 만큼 줄인다 */
    private static SpringAiRetryProperties retry(boolean onClientErrors) {
        SpringAiRetryProperties properties = new SpringAiRetryProperties();
        properties.setMaxAttempts(2);
        properties.getBackoff().setInitialInterval(Duration.ofSeconds(1));
        properties.getBackoff().setMultiplier(2);
        properties.getBackoff().setMaxInterval(Duration.ofSeconds(5));
        properties.setOnClientErrors(onClientErrors);
        return properties;
    }

    private static SpringAiRetryProperties fastRetry() {
        SpringAiRetryProperties properties = retry(false);
        properties.getBackoff().setInitialInterval(Duration.ofMillis(200));
        properties.getBackoff().setMaxInterval(Duration.ofMillis(500));
        return properties;
    }

    /** 진짜 SDK 클라이언트를 가짜 서버에 물린다 — 실패하는 것이 정상이고 보는 것은 «몇 번·얼마나»다 */
    private static void assertThatCallFails(FakeGemini gemini, Duration callTimeout) {
        HttpOptions options =
                GeminiClientConfig.httpOptions(callTimeout, fastRetry()).toBuilder()
                        .baseUrl(gemini.baseUrl())
                        .build();

        /* 키는 ASCII 여야 한다 — OkHttp 가 헤더 값을 그대로 싣다가 한글에서 거절한다 */
        try (Client client = Client.builder().apiKey("test-key").httpOptions(options).build()) {
            client.models.generateContent("gemini-test", "안녕", null);
            throw new AssertionError("가짜 서버는 답하지 않는다 — 여기 오면 안 된다");

        } catch (RuntimeException expected) {
            // 실패의 모양이 아니라 횟수와 시간을 본다
        }
    }

    private static int callsUntilGivingUp(Supplier<RuntimeException> failure) {
        SpringAiRetryProperties properties = retry(false);
        properties.getBackoff().setInitialInterval(Duration.ofMillis(1));
        AtomicInteger calls = new AtomicInteger();
        try {
            new SpringAiRetryAutoConfiguration()
                    .retryTemplate(properties)
                    .execute(
                            context -> {
                                calls.incrementAndGet();
                                throw failure.get();
                            });
        } catch (RuntimeException expected) {
            // 포기한 뒤 마지막 예외가 그대로 나온다
        }
        return calls.get();
    }

    /**
     * Gemini 자리의 가짜 서버.
     *
     * <p>⚠️ <b>기본 executor 는 단일 스레드다</b> — 답하지 않는 핸들러가 다음 테스트를 물고 늘어지므로 {@code setExecutor} 가
     * 필수다({@code McpRestClientTest} 가 같은 자리에서 데였다).
     */
    private static final class FakeGemini implements AutoCloseable {

        private final HttpServer server;
        private final ExecutorService threads =
                Executors.newFixedThreadPool(
                        2,
                        runnable -> {
                            Thread thread = new Thread(runnable, "fake-gemini");
                            // 답하지 않는 핸들러가 잠든 채로 테스트 JVM 의 종료를 붙들지 않게
                            thread.setDaemon(true);
                            return thread;
                        });
        private final AtomicInteger requests = new AtomicInteger();

        private FakeGemini(int status, boolean answer) {
            try {
                server = HttpServer.create(new InetSocketAddress(0), 0);
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
            server.setExecutor(threads);
            server.createContext(
                    "/",
                    exchange -> {
                        requests.incrementAndGet();
                        if (!answer) {
                            sleepUntilTheCallerGivesUp();
                        }
                        byte[] body = "{\"error\":{\"message\":\"가짜\"}}".getBytes();
                        exchange.sendResponseHeaders(status, body.length);
                        try (OutputStream out = exchange.getResponseBody()) {
                            out.write(body);
                        }
                    });
            server.start();
        }

        static FakeGemini thatNeverAnswers() {
            return new FakeGemini(503, false);
        }

        static FakeGemini thatAlwaysReturns(int status) {
            return new FakeGemini(status, true);
        }

        String baseUrl() {
            return "http://localhost:" + server.getAddress().getPort();
        }

        int requests() {
            return requests.get();
        }

        @Override
        public void close() {
            server.stop(0);
            threads.shutdownNow();
        }

        private void sleepUntilTheCallerGivesUp() {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
