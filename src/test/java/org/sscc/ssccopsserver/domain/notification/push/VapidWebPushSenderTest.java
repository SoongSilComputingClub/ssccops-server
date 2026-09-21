package org.sscc.ssccopsserver.domain.notification.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.notification.code.NotificationApp;
import org.sscc.ssccopsserver.domain.notification.entity.PushSubscriptionEntity;
import org.sscc.ssccopsserver.domain.notification.service.WebPushOutcome;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

/*
 * 발송기의 HTTP 계약 (ssccops#446). 푸시 서비스를 JDK HttpServer로 흉내 낸다 — 헤더(RFC 8030 ·
 * 8292)와 상태 코드 → 결과 접기를 본다. 본문 암호화 자체는 WebPushEncryptorTest가 RFC 벡터로 본다.
 *
 * VAPID JWT의 서명은 JDK ECDSA로 **실제로 검증한다**(raw r||s → DER 되돌리기) — 서명 형식을 잘못
 * 내면 푸시 서비스가 401을 주는데 그 실패는 실기기 없이는 보이지 않는다.
 *
 * HttpServer 기본 executor는 단일 스레드라 setExecutor를 준다(AGENTS.md MCP 절의 함정).
 */
class VapidWebPushSenderTest {

    private static final String VAPID_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";
    private static final String VAPID_PUBLIC =
            "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
    private static final String UA_PUBLIC =
            "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    private static final String AUTH_SECRET = "BTBZMqHH6r4Tts7J_aSIgg";
    private static final Instant NOW = Instant.parse("2026-09-22T09:00:00Z");

    private static HttpServer server;
    private static final AtomicInteger statusToAnswer = new AtomicInteger(201);
    private static final Map<String, String> lastHeaders = new ConcurrentHashMap<>();
    private static volatile int lastBodyLength;

    @BeforeAll
    static void startPushService() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext(
                "/push",
                exchange -> {
                    exchange.getRequestHeaders()
                            .forEach((name, values) -> lastHeaders.put(name, values.get(0)));
                    lastBodyLength = exchange.getRequestBody().readAllBytes().length;
                    exchange.sendResponseHeaders(statusToAnswer.get(), -1);
                    exchange.close();
                });
        server.start();
    }

    @AfterAll
    static void stopPushService() {
        server.stop(0);
    }

    @Test
    void sendsAes128gcmBodyWithVapidAuthorizationAndReportsDelivered() throws Exception {
        statusToAnswer.set(201);
        VapidWebPushSender sender = sender();

        WebPushOutcome outcome = sender.send(subscription(), "{\"title\":\"hello\"}");

        assertThat(outcome).isEqualTo(WebPushOutcome.DELIVERED);
        assertThat(lastHeaders.get("Content-encoding")).isEqualTo("aes128gcm");
        assertThat(lastHeaders.get("Content-type")).isEqualTo("application/octet-stream");
        assertThat(lastHeaders.get("Ttl")).isEqualTo("86400");
        assertThat(lastBodyLength).isEqualTo(86 + "{\"title\":\"hello\"}".length() + 1 + 16);

        String authorization = lastHeaders.get("Authorization");
        assertThat(authorization).startsWith("vapid t=").contains(", k=" + VAPID_PUBLIC);
        String jwt = authorization.substring("vapid t=".length(), authorization.indexOf(", k="));
        assertJwtSignedForAudience(jwt, "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Test
    void goneAndNotFoundMeanTheSubscriptionIsDead() {
        VapidWebPushSender sender = sender();

        statusToAnswer.set(410);
        assertThat(sender.send(subscription(), "{}")).isEqualTo(WebPushOutcome.GONE);
        statusToAnswer.set(404);
        assertThat(sender.send(subscription(), "{}")).isEqualTo(WebPushOutcome.GONE);
    }

    @Test
    void otherFailuresAreReportedNotThrown() {
        VapidWebPushSender sender = sender();

        statusToAnswer.set(401);
        assertThat(sender.send(subscription(), "{}")).isEqualTo(WebPushOutcome.FAILED);
        statusToAnswer.set(500);
        assertThat(sender.send(subscription(), "{}")).isEqualTo(WebPushOutcome.FAILED);
    }

    @Test
    void unreachablePushServiceIsAFailureNotAnException() {
        VapidWebPushSender sender = sender();
        PushSubscriptionEntity dead =
                PushSubscriptionEntity.subscribe(
                        member(),
                        NotificationApp.ADMIN,
                        "http://127.0.0.1:1/push",
                        UA_PUBLIC,
                        AUTH_SECRET,
                        null);

        assertThat(sender.send(dead, "{}")).isEqualTo(WebPushOutcome.FAILED);
    }

    @Test
    void unusableSubscriptionKeysCountAsGone() {
        VapidWebPushSender sender = sender();
        PushSubscriptionEntity broken =
                PushSubscriptionEntity.subscribe(
                        member(),
                        NotificationApp.ADMIN,
                        endpoint(),
                        "not-a-key",
                        AUTH_SECRET,
                        null);

        assertThat(sender.send(broken, "{}")).isEqualTo(WebPushOutcome.GONE);
    }

    @Test
    void malformedVapidKeysFailFast() {
        assertThatThrownBy(() -> new VapidCredentials("short", VAPID_PRIVATE, "mailto:a@b.c"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VapidCredentials(VAPID_PUBLIC, "short", "mailto:a@b.c"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VapidCredentials(VAPID_PUBLIC, VAPID_PRIVATE, "a@b.c"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publicKeyIsNormalizedBase64UrlWithoutPadding() {
        VapidCredentials credentials =
                new VapidCredentials(VAPID_PUBLIC + "=", VAPID_PRIVATE, "mailto:a@b.c");
        assertThat(credentials.publicKey()).isEqualTo(VAPID_PUBLIC);
    }

    @Test
    void audienceIsSchemeHostAndPortOnly() {
        assertThat(
                        VapidWebPushSender.audienceOf(
                                URI.create("https://fcm.googleapis.com/fcm/send/abc")))
                .isEqualTo("https://fcm.googleapis.com");
        assertThat(VapidWebPushSender.audienceOf(URI.create("http://localhost:8080/x?y=1")))
                .isEqualTo("http://localhost:8080");
    }

    /* ── helpers ─────────────────────────────────────────────── */

    private static VapidWebPushSender sender() {
        return new VapidWebPushSender(
                new VapidCredentials(VAPID_PUBLIC, VAPID_PRIVATE, "mailto:ops@sscc.org"),
                new WebPushEncryptor(),
                HttpClient.newHttpClient(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/push/abc";
    }

    private static PushSubscriptionEntity subscription() {
        return PushSubscriptionEntity.subscribe(
                member(), NotificationApp.ADMIN, endpoint(), UA_PUBLIC, AUTH_SECRET, null);
    }

    private static MemberEntity member() {
        return MemberEntity.create(
                "20200001", 0, "김도현", null, null, null, "a@sscc.org", null, null, null, null, null);
    }

    private static void assertJwtSignedForAudience(String jwt, String audience) throws Exception {
        String[] parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode header = mapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
        assertThat(header.get("alg").asText()).isEqualTo("ES256");
        assertThat(header.get("typ").asText()).isEqualTo("JWT");
        JsonNode claims = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(claims.get("aud").asText()).isEqualTo(audience);
        assertThat(claims.get("sub").asText()).isEqualTo("mailto:ops@sscc.org");
        assertThat(claims.get("exp").asLong())
                .isEqualTo(NOW.plusSeconds(12 * 3600).getEpochSecond());

        Signature verifier = Signature.getInstance("SHA256withECDSA");
        KeyPair vapid =
                new KeyPair(P256.publicKeyOf(VAPID_PUBLIC), P256.privateKeyOf(VAPID_PRIVATE));
        verifier.initVerify(vapid.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(rawToDer(Base64.getUrlDecoder().decode(parts[2])))).isTrue();
    }

    /** r||s(64바이트) → DER SEQUENCE — JDK 검증기가 읽는 형식 */
    private static byte[] rawToDer(byte[] raw) {
        byte[] rBytes = new BigInteger(1, java.util.Arrays.copyOfRange(raw, 0, 32)).toByteArray();
        byte[] sBytes = new BigInteger(1, java.util.Arrays.copyOfRange(raw, 32, 64)).toByteArray();
        byte[] der = new byte[6 + rBytes.length + sBytes.length];
        der[0] = 0x30;
        der[1] = (byte) (4 + rBytes.length + sBytes.length);
        der[2] = 0x02;
        der[3] = (byte) rBytes.length;
        System.arraycopy(rBytes, 0, der, 4, rBytes.length);
        der[4 + rBytes.length] = 0x02;
        der[5 + rBytes.length] = (byte) sBytes.length;
        System.arraycopy(sBytes, 0, der, 6 + rBytes.length, sBytes.length);
        return der;
    }
}
