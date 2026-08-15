package org.sscc.ssccopsserver.domain.member.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/*
 * 이관 회원 계정 연결의 시도 횟수 제한 (#86 · VR-M24).
 *
 * ── 왜 필요한가 ────────────────────────────────────────────────
 * 실패 응답이 한 코드 한 문구라도(VR-M23) '성공했는가'는 그 자체로 정보다. 제한이 없으면
 * 학번을 바꿔 가며 부르는 것만으로 누가 명부에 있는지를 훑을 수 있고, 연락처가 흔한 형태라
 * 추측 대입도 가능해진다. 응답을 뭉뚱그리는 것과 횟수를 막는 것은 한 쌍이라야 뜻이 있다.
 *
 * ── 제한 단위: 계정당 (IP당이 아니다) ──────────────────────────
 * 연결은 인증을 마친 뒤에만 부를 수 있으므로 요청의 주체는 언제나 소셜 계정 하나다. 막으려는
 * 것이 '한 사람이 학번을 바꿔 가며 명부를 훑는 일'이고 그 사람의 정체가 곧 계정이라, 계정이
 * 정확히 그 축이다. IP는 두 방향으로 어긋난다 — 학교·기숙사 공유망 뒤에서는 무고한 여러 명이
 * 한 카운터를 나눠 쓰다 함께 잠기고, 반대로 공격자는 IP를 바꾸는 편이 계정을 새로 만드는
 * 것보다 훨씬 싸다. 계정 생성에는 소셜 로그인이라는 비용이 붙는다.
 *
 * ── 잠금: 10분 안에 5회 실패하면 10분 ──────────────────────────
 * 5회는 본인이 하이픈 표기나 개명 전 이름을 몇 번 고쳐 보는 여지를 남기는 값이고, 10분은
 * 사람이 기다릴 만하면서 자동 대입의 처리량을 시간당 30회 아래로 끊는 값이다. 명부 규모가
 * 수백 건이므로 이 속도로는 전량을 훑는 데 며칠이 걸린다.
 *
 * **성공하면 카운터를 지운다.** 본인 확인을 통과한 계정에 잠금을 남길 이유가 없다.
 * 이미 다른 계정과 연결된 회원을 만난 경우(409)도 실패로 세지 않는다 — 그 응답에 닿았다는
 * 것 자체가 3종을 모두 맞혔다는 뜻이라 추측이 아니다.
 *
 * ── 한계 ────────────────────────────────────────────────────
 * 새 테이블을 만들지 않고 인메모리로 둔 대가는 분명하다.
 *   · **인스턴스별이다.** 여러 인스턴스로 늘리면 카운터가 인스턴스 수만큼 나뉘어 실질 상한이
 *     그 배수가 된다. 지금은 단일 인스턴스 배포라 성립하며, 늘릴 때는 공유 저장소(Redis 등)로
 *     옮겨야 한다.
 *   · **재기동하면 초기화된다.** 배포가 곧 잠금 해제다.
 *   · **계정을 새로 만들면 초기화된다.** 계정당 제한의 대가이며, 위에 적은 대로 그 비용을
 *     방어선으로 삼는다.
 * 데이터사전에 연결 시도 이력 테이블이 없어 영속화할 자리가 없다 — 필요해지면 테이블 등재가
 * 먼저다(연결 이력을 로그로만 남기는 것과 같은 판단).
 */
@Component
@RequiredArgsConstructor
public class MemberLinkAttemptLimiter {

    /** 잠금까지 허용하는 실패 횟수 */
    static final int MAX_FAILURES = 5;

    /** 실패를 함께 세는 구간. 이 구간을 넘겨 들어온 실패는 새 창을 연다 */
    static final Duration FAILURE_WINDOW = Duration.ofMinutes(10);

    /** 상한에 닿은 뒤 잠기는 시간. 마지막 실패 시각부터 잰다 */
    static final Duration LOCKOUT = Duration.ofMinutes(10);

    /*
     * 추적하는 계정 수의 상한. 넘으면 만료된 항목부터 비운다 — 실패한 계정마다 항목이 남으므로
     * 비우지 않으면 프로세스가 뜬 뒤로 계속 자라기만 한다(만료를 스스로 걷어 주는 캐시가
     * 아니라 맨 ConcurrentHashMap이라 그렇다).
     */
    private static final int MAX_TRACKED_ACCOUNTS = 10_000;

    private final Map<UUID, Attempts> attemptsByAccount = new ConcurrentHashMap<>();

    // 잠금 판정 기준 시각. 테스트에서 고정할 수 있도록 주입받는다 (ClockConfig)
    private final Clock clock;

    /** 지금 이 계정이 잠겨 있는가. 잠겨 있으면 후보 조회 자체를 하지 않는다 */
    public boolean isLocked(UUID authUserId) {
        Attempts attempts = attemptsByAccount.get(authUserId);
        return attempts != null && attempts.isLockedAt(clock.instant());
    }

    /** 본인 확인 실패 한 건을 센다 */
    public void recordFailure(UUID authUserId) {
        purgeIfCrowded();
        Instant now = clock.instant();
        attemptsByAccount.compute(
                authUserId,
                (key, current) ->
                        current == null || current.isExpiredAt(now)
                                ? Attempts.first(now)
                                : current.next(now));
    }

    /** 연결에 성공한 계정의 카운터를 지운다 */
    public void reset(UUID authUserId) {
        attemptsByAccount.remove(authUserId);
    }

    /*
     * 항목 수가 상한을 넘었을 때만 만료된 항목을 걷어낸다. 매 호출마다 훑으면 실패 한 건에
     * 전체 순회가 붙는데, 이 자리는 사람이 폼을 채워 보내는 빈도라 그럴 이유가 없다.
     */
    private void purgeIfCrowded() {
        if (attemptsByAccount.size() < MAX_TRACKED_ACCOUNTS) {
            return;
        }
        Instant now = clock.instant();
        attemptsByAccount.values().removeIf(attempts -> attempts.isExpiredAt(now));
    }

    /*
     * 한 계정의 실패 누적. 창의 시작 시각과 마지막 실패 시각을 함께 들고 있는 것은 둘의 쓰임이
     * 다르기 때문이다 — 창의 시작은 '언제까지 함께 셀 것인가'를, 마지막 실패는 '언제까지
     * 잠글 것인가'를 정한다. 잠금을 창의 시작에서 재면 창이 끝날 무렵 상한에 닿은 경우 잠금이
     * 몇 초 만에 풀린다.
     */
    private record Attempts(int failures, Instant windowStartedAt, Instant lastFailedAt) {

        static Attempts first(Instant now) {
            return new Attempts(1, now, now);
        }

        Attempts next(Instant now) {
            return new Attempts(failures + 1, windowStartedAt, now);
        }

        boolean isLockedAt(Instant now) {
            return failures >= MAX_FAILURES && now.isBefore(lastFailedAt.plus(LOCKOUT));
        }

        /** 더는 셀 이유도 잠글 이유도 없는 항목. 새 실패는 새 창을 연다 */
        boolean isExpiredAt(Instant now) {
            return !isLockedAt(now) && !now.isBefore(windowStartedAt.plus(FAILURE_WINDOW));
        }
    }
}
