package org.sscc.ssccopsserver.domain.assistant.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.assistant.code.error.AssistantErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import lombok.extern.slf4j.Slf4j;

/*
 * 질의 레이트 리밋 — 회원당 분·일, 그리고 전역 분 (#404 · 기획안 §11).
 *
 * ══ 무엇을 지키는가 ════════════════════════════════════════════
 *
 * **무료 쿼터는 API 키 단위의 공유 자원이다.** 한 사람이 루프를 돌리면 전원이 답을 못 받는다.
 * 질의 한 건이 Gemini를 **두 번** 부른다 — 질문 임베딩 한 번과 생성 한 번 — 이므로 층이 셋이다.
 *
 * | 층 | 한도 | 막는 것 |
 * |---|---|---|
 * | 회원당 분 | 5 | 한 사람의 연타 |
 * | 회원당 일 | 50 | 한 사람이 하루치 쿼터를 천천히 태우는 것 |
 * | 전역 분 | Gemini RPM의 70% | 여럿이 동시에 몰리는 것 |
 *
 * **전역 한도를 실제 RPM보다 낮게 잡는 것은 공급자의 429가 아니라 우리 코드가 먼저 끊어 우리
 * 문구로 안내하기 위해서다.** 공급자가 429를 낼 때쯤이면 이미 전역 쿼터가 소진돼 있고, 그 시점의
 * 응답은 503 `ASSISTANT_UPSTREAM_FAILED`(«지금은 답변을 만들 수 없습니다»)라 사용자가 무엇을
 * 하면 되는지 말해 주지 못한다. 429는 «잠시 뒤 다시»라고 말할 수 있다.
 *
 * ⚠️ **전역 한도의 기본값은 잠정이다.** Gemini 무료 티어의 실제 RPM이 아직 실측되지 않았다 —
 * 공개 rate-limits 문서가 「AI Studio 콘솔에서 확인하라」고만 하고 모델별 표를 싣지 않는다
 * (2026-09-15 확인 · ssccops#324의 «아직 안 채운 칸»). 기본값 {@code 7}은 **10 RPM을 가정한
 * 70%**이며, 실측값이 나오면 고치는 것은 코드가 아니라 배포 환경변수 한 줄
 * (`SSCCOPS_ASSISTANT_RATE_LIMIT_GLOBAL_PER_MINUTE`)이다. 낮게 잡힌 채로 두는 쪽이 안전한
 * 실패다 — 덜 답하는 것이지 쿼터를 태우는 것이 아니다.
 *
 * ══ 계정당이지 IP당이 아니다 ═══════════════════════════════════
 *
 * `MemberLinkAttemptLimiter`(#86)와 같은 판단이다. 질의는 인증을 마친 뒤에만 부를 수 있으므로
 * 요청의 주체가 언제나 회원 하나이고, 같은 캠퍼스 네트워크를 IP로 묶으면 무고한 여러 명이 한
 * 카운터를 나눠 쓰다 함께 잠긴다.
 *
 * ══ 적재(회원당 일 10회)는 여기 없다 ═══════════════════════════
 *
 * 그쪽은 **DB가 센다** — `RagDocumentServiceImpl.requireWithinDailyQuota`가 «오늘 그 회원
 * 이름으로 들어간 `rag_doc` 행»을 세며 #399에 이미 서 있다. 기획안이 카운터 셋을 모두 인메모리로
 * 적었지만 적재만 옮기지 않은 것은 **재기동이 곧 한도 초기화가 되면 안 되기 때문**이다: 업로드
 * 한 건이 임베딩을 수백 번 부르고(1.2MB PDF가 184청크), 배포는 하루에도 여러 번 일어난다.
 * 질의는 반대다 — 분·일 창이 짧고 한 건의 비용이 작아 재기동으로 잃는 것이 한 사람의 몇 분치다.
 *
 * ══ 한계 — 인스턴스 하나에 기대고 있다 ═════════════════════════
 *
 * `MemberLinkAttemptLimiter`와 같은 자리이며 대가도 같다.
 *   · **인스턴스별이다.** 여러 인스턴스로 늘리면 카운터가 나뉘어 실질 상한이 그 배수가 된다.
 *     전역 한도는 그 순간 **뜻을 잃는다**(«Gemini RPM의 70%»가 인스턴스마다 70%가 된다) —
 *     늘릴 계획이 생기면 공유 저장소로 옮기기 전에 이 절을 먼저 볼 것(ssccops#324의 마지막 칸).
 *   · **재기동하면 초기화된다.** 배포가 곧 한도 해제다. 위에서 적재를 DB에 둔 이유가 이것이다.
 * Redis를 들이지 않은 것은 인스턴스가 하나이고(ADR-0028), 그 하나를 위해 운영할 저장소가
 * 늘어나는 쪽이 비대칭이기 때문이다.
 */
@Slf4j
@Component
public class AssistantRateLimiter {

    /*
     * 전역 카운터가 앉는 자리. 회원 식별자는 IDENTITY 시퀀스라 음수가 될 수 없으므로 겹치지
     * 않는다 — 캐시를 하나 더 두는 대신 «주인이 없는 창»으로 표현한다. 회원 카운터와 같은
     * 캐시에 살므로 만료 규칙도 하나다.
     */
    private static final long GLOBAL = -1L;

    /*
     * 추적하는 창의 수 상한. 회원 수가 수십이라 실제로는 그 근처에도 가지 않지만, 상한이 없는
     * 캐시는 상한이 없는 맵과 같다 — 인증된 요청만 닿는 자리라 키를 마음대로 늘릴 수는 없어도
     * 만료를 놓쳤을 때의 바닥은 있어야 한다.
     */
    private static final int MAX_TRACKED_WINDOWS = 10_000;

    private final int memberPerMinute;
    private final int memberPerDay;
    private final int globalPerMinute;

    /** 분 단위 창 — 회원 카운터와 전역 카운터가 같은 캐시에 산다(수명이 같다) */
    private final Cache<Window, AtomicInteger> perMinute;

    private final Cache<Window, AtomicInteger> perDay;

    // 창을 가르는 기준 시각. 서비스 표준 시간대로 고정돼 있다 (ClockConfig · AP-12)
    private final Clock clock;

    public AssistantRateLimiter(
            @Value("${ssccops.assistant.rate-limit.member-per-minute}") int memberPerMinute,
            @Value("${ssccops.assistant.rate-limit.member-per-day}") int memberPerDay,
            @Value("${ssccops.assistant.rate-limit.global-per-minute}") int globalPerMinute,
            Clock clock) {

        this.memberPerMinute = memberPerMinute;
        this.memberPerDay = memberPerDay;
        this.globalPerMinute = globalPerMinute;
        this.clock = clock;

        /*
         * **만료는 위생이고 판정은 키가 한다.** 창 번호가 키에 들어 있으므로 분이 바뀌면 다른
         * 항목을 보게 되고, 옛 항목은 다시 읽히지 않은 채 TTL로 사라진다. 그래서 TTL이 정확할
         * 필요가 없다 — 창 길이보다 넉넉히 길기만 하면 된다.
         */
        this.perMinute = build(Duration.ofMinutes(2));
        this.perDay = build(Duration.ofDays(1));

        log.info(
                "규정 도우미 질의 한도 — 회원 분 {}회 · 일 {}회 · 전역 분 {}회",
                memberPerMinute,
                memberPerDay,
                globalPerMinute);
    }

    /**
     * 질의 한 건을 센다 — 한도를 넘었으면 429 {@code ASSISTANT_RATE_LIMITED}로 끊는다.
     *
     * <p><b>모델을 부르기 전에 부른다.</b> 부른 뒤에 세면 그 시점엔 이미 쿼터를 썼고, 막으려던 것이 그 지출이다.
     *
     * <p><b>거절은 아무 카운터도 올리지 않는다.</b> 한 층에 걸린 요청이 다른 층의 예산까지 태우면, 전역이 붐비는 동안 아무도 묻지 않은 회원의 일 한도가 조용히
     * 줄어든다.
     *
     * <p><b>세는 것은 «답한 질의»가 아니라 «받아들인 질의»다.</b> 근거를 찾지 못해 거절로 끝난 질의도 질문 임베딩 한 번을 이미 불렀다 — 코퍼스가 통째로
     * 비어 그조차 부르지 않는 경우만 예외인데, 그 한 경우를 위해 «어디까지 갔는가»를 되짚어 세면 세는 자리가 여러 곳으로 흩어진다.
     *
     * <p>{@code synchronized}인 것은 <b>세 층을 함께 보고 함께 올려야</b> 하기 때문이다. 층마다 원자적으로 올리는 것만으로는 「다 통과했는데
     * 올리고 보니 넘어 있다」를 막지 못한다. 이 자리의 처리량이 분당 한 자릿수라 잠금 비용이 보이지 않고, 안에서 I/O를 하지 않는다.
     */
    public synchronized void requireWithinQuota(long memberId) {
        long minute = clock.instant().getEpochSecond() / 60;
        long day = LocalDate.now(clock).toEpochDay();

        AtomicInteger memberMinuteCount = counter(perMinute, new Window(memberId, minute));
        AtomicInteger memberDayCount = counter(perDay, new Window(memberId, day));
        AtomicInteger globalMinuteCount = counter(perMinute, new Window(GLOBAL, minute));

        if (memberMinuteCount.get() >= memberPerMinute) {
            throw rejected(
                    memberId,
                    "회원 분",
                    "질문은 1분에 %d번까지 할 수 있습니다. 잠시 뒤 다시 물어봐 주세요.".formatted(memberPerMinute));
        }
        if (memberDayCount.get() >= memberPerDay) {
            throw rejected(
                    memberId,
                    "회원 일",
                    "하루에 물을 수 있는 질문은 %d개입니다. 내일 다시 물어봐 주세요.".formatted(memberPerDay));
        }
        if (globalMinuteCount.get() >= globalPerMinute) {
            /*
             * **사용자를 탓하지 않는 문구다.** 이 사람은 한도를 넘지 않았고 바꿀 수 있는 것도
             * 없다 — 전역 한도라는 사실을 문구로 설명해 봐야 할 일이 «잠시 뒤 다시»로 같다.
             */
            throw rejected(memberId, "전역 분", "지금 문의가 몰려 있습니다. 잠시 뒤 다시 물어봐 주세요.");
        }

        memberMinuteCount.incrementAndGet();
        memberDayCount.incrementAndGet();
        globalMinuteCount.incrementAndGet();
    }

    private Cache<Window, AtomicInteger> build(Duration retention) {
        return Caffeine.newBuilder()
                .expireAfterWrite(retention)
                .maximumSize(MAX_TRACKED_WINDOWS)
                .build();
    }

    /*
     * 창 하나의 카운터. 없으면 만든다 — 다른 층에서 거절될 요청도 항목을 만들지만 값이 0인 채로
     * 남을 뿐이고, 그 창이 지나면 TTL이 걷어 간다. 세 층을 **먼저 다 꺼내 놓고** 판정하는 것은
     * 통과했을 때 세 값을 한꺼번에 올리기 위해서다(위 «함께 보고 함께 올린다»).
     */
    private AtomicInteger counter(Cache<Window, AtomicInteger> cache, Window window) {
        return cache.get(window, key -> new AtomicInteger());
    }

    /*
     * **질문을 로그에 싣지 않는다** — 질문에는 사람 이름이 섞여 들어올 수 있고 로그는 Kibana에
     * 남는다(ADR-0024 · §11). 남기는 것은 «누가·어느 층에서 걸렸나»다. WARN이 아니라 INFO인 것은
     * 이것이 설계대로 동작한 결과이지 고장이 아니기 때문이다.
     */
    private GeneralException rejected(long memberId, String layer, String message) {
        log.info("규정 도우미 질의 한도 초과 — mbrId={} 층={}", memberId, layer);
        return new GeneralException(AssistantErrorCode.ASSISTANT_RATE_LIMITED, message);
    }

    /** 카운터 하나의 자리 — «누구의, 어느 창인가». {@code owner}가 {@link #GLOBAL}이면 전역이다 */
    private record Window(long owner, long bucket) {}
}
