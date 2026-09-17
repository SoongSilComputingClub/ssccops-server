package org.sscc.ssccopsserver.domain.assistant.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;
import org.sscc.ssccopsserver.domain.assistant.code.error.AssistantErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

/*
 * 질의 한도의 규칙 — 층 셋과 창이 넘어가는 시점 (#404 · 기획안 §11).
 *
 * ══ 왜 컨텍스트 없이 보는가 ═════════════════════════════════════
 *
 * 확인하려는 것이 배선이 아니라 **시각에 매인 규칙**이다. 분·일 창이 실제로 넘어가는지를 보려면
 * 시계를 옮겨야 하는데, 스프링을 띄우면 그 시계가 `ClockConfig`의 시스템 시계이거나 고정 `Clock`
 * 이라 «1분 뒤»를 만들 수가 없다 — 진짜로 기다리는 테스트는 CI에서 느리고 불안정하다.
 *
 * **회원을 목으로 만들지도 않는다.** 한도가 아는 것은 식별자 하나뿐이고, 그것이 이 층이
 * 회원 엔티티를 모른다는 사실의 표현이다.
 *
 * ⚠️ 하루 경계는 **서비스 표준 시간대**(Asia/Seoul · `ClockConfig` · AP-12)로 가른다. 그래서
 * 이 테스트도 그 시간대의 시계를 쓴다 — UTC 시계로 쓰면 오후 늦은 시각의 판정이 하루 어긋난다.
 */
class AssistantRateLimiterTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private static final long MEMBER = 7L;

    /** 2026-09-15 화요일 10:30 KST — 하루 경계에서 멀리 떨어진 평범한 시각 */
    private final MovableClock clock =
            new MovableClock(ZonedDateTime.of(2026, 9, 15, 10, 30, 0, 0, SEOUL).toInstant());

    // ------------------------------------------------------------------ 회원당 분

    /* 분 한도를 넘는 순간 429이고, 그 뒤로는 창이 바뀌기 전까지 계속 429다 */
    @Test
    void stopsTheMemberAtTheMinuteLimit() {
        AssistantRateLimiter limiter = limiter(5, 50, 1000);

        for (int attempt = 0; attempt < 5; attempt++) {
            limiter.requireWithinQuota(MEMBER);
        }

        assertThatThrownBy(() -> limiter.requireWithinQuota(MEMBER))
                .isInstanceOf(GeneralException.class)
                .hasMessageContaining("1분에 5번")
                .extracting(exception -> ((GeneralException) exception).getErrorCode())
                .isEqualTo(AssistantErrorCode.ASSISTANT_RATE_LIMITED);
    }

    /* 분이 바뀌면 다시 물을 수 있다 — 창이 키에 들어 있어 옛 카운터를 다시 읽지 않는다 */
    @Test
    void opensANewWindowWhenTheMinuteRollsOver() {
        AssistantRateLimiter limiter = limiter(2, 50, 1000);

        limiter.requireWithinQuota(MEMBER);
        limiter.requireWithinQuota(MEMBER);
        assertThatThrownBy(() -> limiter.requireWithinQuota(MEMBER))
                .isInstanceOf(GeneralException.class);

        clock.advance(Duration.ofMinutes(1));

        assertThatCode(() -> limiter.requireWithinQuota(MEMBER)).doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------ 회원당 일

    /*
     * **일 한도는 분이 바뀌어도 살아 있다** — 분 한도만 있으면 하루 종일 1분에 5번씩 물어
     * 쿼터를 천천히 태울 수 있다. 그 둘이 함께 있어야 뜻이 있다.
     */
    @Test
    void keepsCountingAcrossMinutesUntilTheDailyLimit() {
        AssistantRateLimiter limiter = limiter(1, 3, 1000);

        for (int attempt = 0; attempt < 3; attempt++) {
            limiter.requireWithinQuota(MEMBER);
            clock.advance(Duration.ofMinutes(1));
        }

        assertThatThrownBy(() -> limiter.requireWithinQuota(MEMBER))
                .as("분은 새 창인데 일 한도가 남아 있다")
                .isInstanceOf(GeneralException.class)
                .hasMessageContaining("하루에 물을 수 있는 질문은 3개")
                .hasMessageContaining("내일");
    }

    /*
     * 하루 경계는 **자정**이다(서비스 표준 시간대). 첫 질의로부터 24시간이 아니라 날짜가 바뀌면
     * 열리는 것이, «내일 다시 물어봐 주세요»라는 문구가 참이 되는 유일한 규칙이고 적재 한도
     * (`rag_doc` 행을 오늘 자정부터 센다 · #399)와도 같은 경계다.
     */
    @Test
    void resetsTheDailyWindowAtMidnight() {
        AssistantRateLimiter limiter = limiter(1000, 1, 1000);

        limiter.requireWithinQuota(MEMBER);
        assertThatThrownBy(() -> limiter.requireWithinQuota(MEMBER))
                .isInstanceOf(GeneralException.class);

        clock.advance(Duration.ofHours(13)); // 10:30 → 23:30, 아직 같은 날
        assertThatThrownBy(() -> limiter.requireWithinQuota(MEMBER))
                .as("자정 전에는 그대로 막힌다")
                .isInstanceOf(GeneralException.class);

        clock.advance(Duration.ofHours(1)); // 다음 날 00:30
        assertThatCode(() -> limiter.requireWithinQuota(MEMBER)).doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------ 전역 분

    /*
     * **전역 한도는 회원을 가리지 않는다** — 막으려는 것이 «한 사람»이 아니라 «API 키 하나에
     * 몰리는 요청»이라서다.
     *
     * 문구가 회원 한도와 다른 것도 그래서다. 이 사람은 한도를 넘지 않았고 바꿀 수 있는 것도
     * 없으므로 «당신이 너무 많이 물었다»고 말하지 않는다.
     */
    @Test
    void stopsEveryoneAtTheGlobalMinuteLimit() {
        AssistantRateLimiter limiter = limiter(1000, 1000, 3);

        limiter.requireWithinQuota(1L);
        limiter.requireWithinQuota(2L);
        limiter.requireWithinQuota(3L);

        assertThatThrownBy(() -> limiter.requireWithinQuota(4L))
                .isInstanceOf(GeneralException.class)
                .hasMessageContaining("문의가 몰려 있습니다")
                .hasMessageNotContaining("1분에");
    }

    /*
     * **거절된 요청은 어느 카운터도 올리지 않는다.**
     *
     * 전역이 붐비는 동안 아무도 답을 못 받는데 그 실패가 각자의 일 한도까지 깎으면, 붐빔이
     * 가신 뒤에 «오늘은 이미 다 썼습니다»가 남는다 — 쓴 적이 없는 쿼터다.
     */
    @Test
    void spendsNothingWhenTheRequestIsRejected() {
        AssistantRateLimiter limiter = limiter(1000, 2, 1);

        limiter.requireWithinQuota(1L); // 남이 이번 분의 전역 한 칸을 가져갔다

        assertThatThrownBy(() -> limiter.requireWithinQuota(MEMBER))
                .as("전역 한도에서 걸린다 — 이 회원은 아직 한 번도 묻지 않았다")
                .isInstanceOf(GeneralException.class);

        clock.advance(Duration.ofMinutes(1));
        assertThatCode(() -> limiter.requireWithinQuota(MEMBER)).doesNotThrowAnyException();

        clock.advance(Duration.ofMinutes(1));
        assertThatCode(() -> limiter.requireWithinQuota(MEMBER))
                .as("일 한도 2회가 방금 전이 아니라 여기서 차야 한다 — 걸린 요청이 깎았다면 이미 넘었다")
                .doesNotThrowAnyException();

        clock.advance(Duration.ofMinutes(1));
        assertThatThrownBy(() -> limiter.requireWithinQuota(MEMBER))
                .as("그리고 여기서 비로소 일 한도다")
                .isInstanceOf(GeneralException.class)
                .hasMessageContaining("하루에");
    }

    // ------------------------------------------------------------------ 층의 순서

    /*
     * 세 층에 동시에 걸리면 **회원 분 → 회원 일 → 전역** 순으로 판정한다.
     *
     * 순서 자체가 규칙이라기보다 **문구가 구체적인 쪽부터** 말하기 위한 것이다 — 사용자가 할 수
     * 있는 일이 있는 층(내가 너무 빨리 물었다)을 먼저 알려 준다.
     */
    @Test
    void reportsTheMostSpecificLayerFirst() {
        AssistantRateLimiter limiter = limiter(1, 1, 1);

        limiter.requireWithinQuota(MEMBER);

        assertThatThrownBy(() -> limiter.requireWithinQuota(MEMBER))
                .isInstanceOf(GeneralException.class)
                .hasMessageContaining("1분에 1번");
    }

    // ------------------------------------------------------------------ 픽스처

    private AssistantRateLimiter limiter(int perMinute, int perDay, int globalPerMinute) {
        return new AssistantRateLimiter(perMinute, perDay, globalPerMinute, clock);
    }

    /*
     * 옮길 수 있는 시계. `Clock.fixed`로는 창이 넘어가는 순간을 만들 수 없고, 실제로 기다리는
     * 테스트는 CI에서 느리고 불안정하다 — 시각을 주입해 두었기에 가능한 방법이다(ClockConfig).
     */
    private static final class MovableClock extends Clock {

        private Instant now;

        private MovableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return SEOUL;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("시간대를 바꿔 쓰지 않는다");
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
