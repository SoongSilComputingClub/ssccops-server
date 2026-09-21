package org.sscc.ssccopsserver.domain.notification.push;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

import org.sscc.ssccopsserver.domain.notification.entity.PushSubscriptionEntity;
import org.sscc.ssccopsserver.domain.notification.service.WebPushOutcome;
import org.sscc.ssccopsserver.domain.notification.service.WebPushSender;

import lombok.extern.slf4j.Slf4j;

/*
 * 표준 Web Push 발송기 — RFC 8030(전송) · 8291(암호화) · 8292(VAPID) (ssccops#446 · ADR-0045).
 *
 * 구독의 endpoint로 `POST`를 보낸다. 본문은 `WebPushEncryptor`가 만든 `aes128gcm` 바이트이고,
 * `Authorization: vapid t=…, k=…`는 `VapidCredentials`가 endpoint의 origin을 aud로 서명한다.
 * HTTP 클라이언트는 JDK의 것이다 — 이 서버가 나가는 HTTP를 쓰는 곳은 MCP 자기 호출(RestClient)뿐이고
 * 그쪽은 `ApiResponse` 봉투 처리가 붙어 있어 여기서 재사용할 것이 없다. 연결 5초·요청 10초.
 *
 * **상태 코드는 셋으로 접는다**(`WebPushOutcome`). 201(대부분의 서비스)·200·202는 DELIVERED,
 * 404·410은 GONE(구독이 죽었다 — 호출부가 행을 지운다 · RFC 8030 §7.3), 나머지는 FAILED.
 * FAILED의 대표 원인은 둘이다 — 401/403은 VAPID 키가 구독 때의 키와 다르다(키를 바꿨거나 dev·prod
 * 키가 섞였다), 413은 페이로드 초과. 429·5xx는 서비스 쪽이며 재시도하지 않는다(다음 알림이 곧 다음
 * 시도이고, 잃는 것은 푸시 한 번이지 알림 행이 아니다).
 *
 * **로그에 endpoint를 싣지 않는다.** capability URL이라 아는 사람은 누구나 그 브라우저에 보낼 수
 * 있다. 구독 행 id와 상태 코드만 남긴다.
 *
 * `TTL: 86400` — 브라우저가 오프라인이면 푸시 서비스가 하루까지 들고 있는다. 마감 D-1 알림이
 * 다음 날 도착해도 뜻이 남는 시간이다. `Urgency: normal`은 기본값이라 적지 않는다.
 */
@Slf4j
public class VapidWebPushSender implements WebPushSender {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final String TTL_SECONDS = "86400";

    private final VapidCredentials credentials;
    private final WebPushEncryptor encryptor;
    private final HttpClient httpClient;
    private final Clock clock;

    public VapidWebPushSender(String publicKey, String privateKey, String subject, Clock clock) {
        this(
                new VapidCredentials(publicKey, privateKey, subject),
                new WebPushEncryptor(),
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(),
                clock);
    }

    VapidWebPushSender(
            VapidCredentials credentials,
            WebPushEncryptor encryptor,
            HttpClient httpClient,
            Clock clock) {
        this.credentials = credentials;
        this.encryptor = encryptor;
        this.httpClient = httpClient;
        this.clock = clock;
    }

    @Override
    public Optional<String> publicKey() {
        return Optional.of(credentials.publicKey());
    }

    @Override
    public WebPushOutcome send(PushSubscriptionEntity subscription, String payloadJson) {
        URI endpoint;
        byte[] body;
        try {
            endpoint = URI.create(subscription.getEndpoint());
            body =
                    encryptor.encrypt(
                            payloadJson.getBytes(StandardCharsets.UTF_8),
                            subscription.getP256dhKey(),
                            subscription.getAuthKey());
        } catch (RuntimeException e) {
            // 구독 값 자체가 깨졌다(키 길이·곡선 밖의 점·URI). 보내 봐야 같은 답이라 죽은 구독으로 본다
            log.warn(
                    "push subscription {} has unusable keys — treating as gone: {}",
                    subscription.getId(),
                    e.getMessage());
            return WebPushOutcome.GONE;
        }

        HttpRequest request =
                HttpRequest.newBuilder(endpoint)
                        .timeout(REQUEST_TIMEOUT)
                        .header("Content-Type", "application/octet-stream")
                        .header("Content-Encoding", "aes128gcm")
                        .header("TTL", TTL_SECONDS)
                        .header(
                                "Authorization",
                                credentials.authorizationHeader(
                                        audienceOf(endpoint), clock.instant()))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build();

        try {
            HttpResponse<Void> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return outcomeOf(subscription, response.statusCode());
        } catch (IOException e) {
            log.error(
                    "push send failed for subscription {} — network: {}",
                    subscription.getId(),
                    e.getMessage());
            return WebPushOutcome.FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return WebPushOutcome.FAILED;
        }
    }

    private WebPushOutcome outcomeOf(PushSubscriptionEntity subscription, int status) {
        if (status == 200 || status == 201 || status == 202) {
            return WebPushOutcome.DELIVERED;
        }
        if (status == 404 || status == 410) {
            log.info("push subscription {} is gone ({}) — deleting", subscription.getId(), status);
            return WebPushOutcome.GONE;
        }
        log.error(
                "push send failed for subscription {} — push service answered {}{}",
                subscription.getId(),
                status,
                status == 401 || status == 403 ? " (VAPID 키가 구독 때의 키와 다르다 — 키를 바꿨거나 환경이 섞였다)" : "");
        return WebPushOutcome.FAILED;
    }

    /** RFC 8292 §2 — aud는 endpoint의 origin(scheme://host[:port])이다 */
    static String audienceOf(URI endpoint) {
        StringBuilder audience =
                new StringBuilder(endpoint.getScheme()).append("://").append(endpoint.getHost());
        if (endpoint.getPort() != -1) {
            audience.append(':').append(endpoint.getPort());
        }
        return audience.toString();
    }
}
