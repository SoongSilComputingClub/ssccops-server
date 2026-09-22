package org.sscc.ssccopsserver.domain.notification.service;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.notification.code.error.NotificationErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import lombok.extern.slf4j.Slf4j;

/*
 * 테스트 알림 한도 — 회원당 1분 3회 (#528 · ssccops#454).
 *
 * 막는 것은 «버튼 연타»다. 테스트 알림 한 건이 그 회원의 구독 전부로 푸시 서비스를 동기 호출하므로,
 * 연타는 곧 푸시 서비스 호출 연타이고 그쪽 429가 VAPID 키 단위로 오면 남의 알림까지 함께 막힌다.
 * 세 번이면 «오나 안 오나»를 확인하기에 충분하다.
 *
 * 모양은 `AssistantRateLimiter`(#404)를 줄인 것이다 — 고정 분 창(창 번호가 키에 든다) · Caffeine
 * 카운터 · 거절은 카운터를 올리지 않는다 · 계정당이지 IP당이 아니다(인증 뒤에만 부를 수 있다).
 * 그 클래스를 재사용하지 않은 것은 그쪽 한도가 규정 도우미의 쿼터 사정으로 정해진 값이고 층이
 * 셋이라(회원 분·일 · 전역 분) 여기 필요한 한 층과 맞지 않기 때문이다. 공용 «범용 리미터»로
 * 빼는 안은 기각 — 두 자리뿐이고 뜻이 다른 두 한도를 한 클래스의 인자로 만들면 어느 값이 어느
 * 이유인지가 코드에서 사라진다.
 *
 * 한계도 같다 — 인스턴스별이고 재기동하면 초기화된다. 테스트 알림에는 그 대가가 없다.
 */
@Slf4j
@Component
public class TestNotificationRateLimiter {

    static final int PER_MINUTE = 3;

    /* 추적하는 창의 수 상한 — 만료를 놓쳤을 때의 바닥(AssistantRateLimiter와 같은 판단) */
    private static final int MAX_TRACKED_WINDOWS = 10_000;

    /* 만료는 위생이고 판정은 키가 한다 — 창 길이보다 넉넉히 길기만 하면 된다 */
    private final Cache<Window, AtomicInteger> perMinute =
            Caffeine.newBuilder()
                    .expireAfterWrite(Duration.ofMinutes(2))
                    .maximumSize(MAX_TRACKED_WINDOWS)
                    .build();

    private final Clock clock;

    public TestNotificationRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * 테스트 알림 한 건을 센다 — 한도를 넘었으면 429 {@code RATE_LIMITED}로 끊는다.
     *
     * <p>알림 행을 만들기 전에 부른다. 만든 뒤에 세면 그 시점엔 이미 푸시가 나갔고, 막으려던 것이 그 발송이다. {@code synchronized}인 것은 판정과
     * 증가를 함께 하기 위해서다 — 처리량이 분당 한 자릿수라 잠금 비용이 보이지 않는다.
     */
    public synchronized void requireWithinQuota(long memberId) {
        long minute = clock.instant().getEpochSecond() / 60;
        AtomicInteger count =
                perMinute.get(new Window(memberId, minute), key -> new AtomicInteger());
        if (count.get() >= PER_MINUTE) {
            // INFO — 설계대로 동작한 결과이지 고장이 아니다
            log.info("테스트 알림 한도 초과 — mbrId={}", memberId);
            throw new GeneralException(NotificationErrorCode.TEST_NOTIFICATION_RATE_LIMITED);
        }
        count.incrementAndGet();
    }

    /** 카운터 하나의 자리 — «누구의, 어느 분인가» */
    private record Window(long memberId, long minute) {}
}
