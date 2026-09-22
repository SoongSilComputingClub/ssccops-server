package org.sscc.ssccopsserver.domain.notification.push;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.sscc.ssccopsserver.domain.notification.service.WebPushSender;

import lombok.extern.slf4j.Slf4j;

/*
 * 어느 발송기가 서는가 (ssccops#446 · ADR-0045).
 *
 * `ssccops.push.enabled`가 켜져 있고 VAPID 세 값이 전부 있으면 `VapidWebPushSender`, 아니면
 * `NoopWebPushSender`다. **키가 비어 있어도 부팅한다** — 키를 아직 발급하지 않은 배포·기여자
 * 로컬·CI가 전부 정당한 상태라 `AppPublicBaseUrl`처럼 세우지 않는다(Gemini 배선과 같은 판단).
 * 대신 **조용히 끄지 않는다** — 어느 쪽이 섰는지 부팅 로그 한 줄로 알린다. 키가 있는데 형식이
 * 틀리면 그때는 세운다(`VapidCredentials`) — 틀린 키는 «전 구독 401»로만 드러나기 때문이다.
 *
 * 값의 선언과 기본값은 `application.yaml`의 `ssccops.push` 블록 한 곳이다(규정 도우미 손잡이와
 * 같은 규칙 · #439). 환경변수 `SSCCOPS_PUSH_VAPID_PUBLIC_KEY`·`…_PRIVATE_KEY`·`…_SUBJECT`.
 *
 * ── 왜 `nl.martijndwars:web-push`가 아닌가 ────────────────────────────────
 * ADR-0045·#446이 1순위로 적은 라이브러리이고 «Java 21과 안 맞으면 직접 구현»이 허용된 대안이었다.
 * 5.1.1의 POM을 실제로 봤다 — 컴파일 의존이 `async-http-client 2.10`(netty 전부) ·
 * `httpasyncclient 4.1`(Apache HC 4 — 이 서버는 HC 5 계열) · `jcommander`(CLI 파서) · `jose4j`이고,
 * 정작 암호화에 필요한 BouncyCastle은 선언하지 않고 **사용자가 `Security.addProvider`로 전역
 * 등록하기를 요구한다.** 우리에게 필요한 것은 RFC 8291 한 레코드 암호화와 ES256 서명 하나인데
 * 그것은 JDK 17 표준 프로바이더로 전부 되고(`P256`·`WebPushEncryptor`·`VapidCredentials` 세
 * 클래스, 합쳐 300줄 미만), 그 대가로 netty·HC4·CLI 파서를 jar에 싣고 JVM 전역 프로바이더
 * 순서를 바꾸는 것은 «지금 없는 것을 위해 비용을 내지 않는다»(ADR-0045)와 반대다. 직접 구현은
 * RFC 8291 부록 A의 값과 바이트 단위로 대조된다(`WebPushEncryptorTest`). 발송기 인터페이스
 * (`WebPushSender`)는 그 이슈가 말한 대로 유지했다 — 라이브러리로 되돌리는 것은 이 파일과
 * `push/` 안의 일이다.
 */
@Slf4j
@Configuration
public class WebPushSenderConfig {

    @Bean
    public WebPushSender webPushSender(
            @Value("${ssccops.push.enabled}") boolean enabled,
            @Value("${ssccops.push.vapid.public-key}") String publicKey,
            @Value("${ssccops.push.vapid.private-key}") String privateKey,
            @Value("${ssccops.push.vapid.subject}") String subject,
            Clock clock) {
        if (!enabled) {
            log.info("web push disabled (ssccops.push.enabled=false) — 알림 행만 만들고 푸시는 보내지 않는다");
            return new NoopWebPushSender();
        }
        if (isBlank(publicKey) || isBlank(privateKey) || isBlank(subject)) {
            log.warn(
                    "web push disabled — VAPID 키가 없다 (SSCCOPS_PUSH_VAPID_PUBLIC_KEY ·"
                            + " _PRIVATE_KEY · _SUBJECT). 알림 행만 만들고 푸시는 보내지 않는다");
            return new NoopWebPushSender();
        }
        VapidWebPushSender sender =
                new VapidWebPushSender(publicKey.trim(), privateKey.trim(), subject.trim(), clock);
        log.info("web push enabled — VAPID subject {}", subject.trim());
        return sender;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
