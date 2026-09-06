package org.sscc.ssccopsserver.domain.operation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

/*
 * 지연 판정 경계(#121)의 단위 검증. 단건 응답(SubWorkEntity.isDelayedBefore)과 목록 필터
 * (SubWorkRepositoryImpl)가 모두 이 값을 받아 쓰므로, 경계 자체가 언제 바뀌는지는 여기가
 * 유일한 명세다.
 *
 * 스프링을 띄우지 않는다 — 필요한 것은 Clock 하나뿐이다. 자정을 사이에 둔 시각들로 Clock을
 * 고정하는 것이 이 테스트의 전부다.
 */
class DeadlinePolicyTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // 2026-08-20T12:00+09:00 (= 03:00Z). 그날의 경계는 2026-08-20T00:00+09:00 (= 전날 15:00Z)다
    private static final Instant NOON_KST = Instant.parse("2026-08-20T03:00:00Z");
    private static final Instant MIDNIGHT_KST = Instant.parse("2026-08-19T15:00:00Z");

    // 경계는 '지금'이 아니라 서비스 표준 시간대의 오늘 0시다
    @Test
    void overdueBeforeIsStartOfTodayInServiceZone() {
        DeadlinePolicy policy = new DeadlinePolicy(Clock.fixed(NOON_KST, KST));

        assertThat(policy.overdueBefore()).isEqualTo(MIDNIGHT_KST);
    }

    // 자정 정각에도 그날의 0시다 — 경계가 하루 앞으로 밀리면 어제 마감이 지연에서 빠진다
    @Test
    void overdueBeforeAtMidnightIsThatMidnight() {
        DeadlinePolicy policy = new DeadlinePolicy(Clock.fixed(MIDNIGHT_KST, KST));

        assertThat(policy.overdueBefore()).isEqualTo(MIDNIGHT_KST);
    }

    /*
     * 자정 1분 전에는 아직 어제의 경계다. 이 두 케이스가 함께 있어야 "마감일 다음 날 0시부터
     * 지연"이 못 박힌다 — 마감이 오늘인 건은 오늘 23:59까지 지연이 아니고, 그 1분 뒤에 된다.
     */
    @Test
    void overdueBeforeJustBeforeMidnightIsStillPreviousDay() {
        DeadlinePolicy policy = new DeadlinePolicy(Clock.fixed(MIDNIGHT_KST.minusSeconds(60), KST));

        assertThat(policy.overdueBefore()).isEqualTo(MIDNIGHT_KST.minusSeconds(86_400));
    }

    /*
     * 시간대는 Clock에서 온다 (ClockConfig가 Asia/Seoul로 만든다). UTC로 고정하면 같은 순간에도
     * 경계가 아홉 시간 어긋나므로, 판정이 시스템 기본 시간대를 보고 있지 않은지 여기서 걸린다.
     */
    @Test
    void overdueBeforeFollowsTheClockZone() {
        DeadlinePolicy policy = new DeadlinePolicy(Clock.fixed(NOON_KST, ZoneId.of("UTC")));

        assertThat(policy.overdueBefore()).isEqualTo(Instant.parse("2026-08-20T00:00:00Z"));
    }

    /*
     * 검토 정체 경계 (ssccops#196). 오늘이 8/20이면 경계는 8/18 0시다 — 8/18에 올린 요청이
     * 오늘로 3일째이므로 정체이고, 8/19에 올린 요청은 2일째라 아니다.
     *
     * 임계값이 여기 한 곳에만 있어야 목록 필터와 응답 판정이 같은 날을 말한다. 이 값을
     * 화면이나 쿼리 파라미터로 옮기면 대시보드와 목록이 같은 건을 다르게 센다.
     */
    @Test
    void reviewStaleBeforeIsThreeDaysBackInServiceZone() {
        DeadlinePolicy policy = new DeadlinePolicy(Clock.fixed(NOON_KST, KST));

        assertThat(policy.reviewStaleBefore()).isEqualTo(MIDNIGHT_KST.minusSeconds(2 * 86_400));
    }

    /*
     * 지연과 같은 이유로 시각이 아니라 일자로 잰다 — 정오에 물어도 자정에 물어도 그날의
     * 경계는 같다. 초 단위로 재면 "오전에는 아직 아니고 오후에는 정체"인 건이 생긴다.
     */
    @Test
    void reviewStaleBeforeDoesNotMoveWithinTheSameDay() {
        Instant boundary = new DeadlinePolicy(Clock.fixed(NOON_KST, KST)).reviewStaleBefore();

        assertThat(new DeadlinePolicy(Clock.fixed(MIDNIGHT_KST, KST)).reviewStaleBefore())
                .isEqualTo(boundary);
    }

    // 며칠을 정체로 볼지는 서버 상수 하나다. 값이 바뀌면 이 테스트가 먼저 알린다
    @Test
    void reviewStaleThresholdIsThreeDays() {
        assertThat(DeadlinePolicy.REVIEW_STALE_DAYS).isEqualTo(3);
    }
}
