package org.sscc.ssccopsserver.domain.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

/*
 * 대화 이력 캐시의 규칙 — **상한과 만료** (#406 · 기획안 §7.2 · §7.3 · §8.1).
 *
 * ══ 만료를 실제로 재는 것이 이 클래스의 일이다 ══════════════════
 *
 * 기획안이 «외부 저장소보다 오히려 쉬운 자리»라고 적은 것이 여기다 — Caffeine의 `ticker`에 이
 * 레포가 이미 주입받는 `Clock`을 물려 두었으므로(ClockConfig · AP-12) 24시간을 **옮겨서** 본다.
 * 진짜로 기다리는 테스트는 CI에서 느리고 불안정하며, Redis였다면 아예 붙일 수 없는 확인이다.
 *
 * ⚠️ **상한이 메모리를 보증하고 TTL은 위생이다**(§7.3). 그래서 여기서 못 박는 첫째는 «대화가
 * 아무리 늘어도 상한을 넘지 않는다»이고, 그것이 없으면 크기가 아니라 **누수**가 문제가 된다 —
 * Spring AI의 `InMemoryChatMemoryRepository`를 그대로 쓰지 않은 이유가 그 한 줄이다.
 */
class AssistantMemoryStoreTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private static final Duration DAY = Duration.ofHours(24);

    /** 2026-09-15 화요일 10:30 KST — 하루 경계에서 멀리 떨어진 평범한 시각 */
    private final MovableClock clock =
            new MovableClock(ZonedDateTime.of(2026, 9, 15, 10, 30, 0, 0, SEOUL).toInstant());

    // ------------------------------------------------------------------ 상한

    /*
     * **대화가 아무리 늘어도 상한을 넘지 않는다** — §8.1의 12MB를 실제로 지키는 한 줄.
     *
     * *어느* 대화가 남는지는 보지 않는다. Caffeine의 축출은 엄격한 LRU가 아니라
     * Window-TinyLFU라 «가장 오래된 것»이 남을 수도 있고, 우리가 기대는 성질은 순서가 아니라
     * **천장**이다. 순서까지 못 박으면 캐시 구현의 내부 규칙을 검증하게 된다.
     */
    @Test
    void neverHoldsMoreConversationsThanTheCap() {
        AssistantMemoryStore store = store(10, DAY);

        for (int index = 0; index < 25; index++) {
            store.saveAll("7:conversation-" + index, turn("질문 " + index, "답 " + index));
        }

        assertThat(store.findConversationIds()).hasSizeLessThanOrEqualTo(10).isNotEmpty();
    }

    // ------------------------------------------------------------------ 만료

    /*
     * 24시간이 지나면 사라진다 — **그 전까지는 그대로 있다.**
     *
     * 대화 둘을 같은 시각에 담고 한쪽만 23시간째에 읽는다. ⚠️ **그 조회 자체가 창을 다시 여는
     * 것**이 `expireAfterAccess`의 뜻이라, 25시간째에 사라지는 것은 읽지 않은 쪽뿐이다 — 아래
     * 슬라이딩 테스트가 그 성질을 정면으로 보고, 여기서는 만료가 실제로 일어난다는 사실을 본다.
     */
    @Test
    void forgetsAConversationThatGoesUnusedForADay() {
        AssistantMemoryStore store = store(500, DAY);
        store.saveAll("7:asked", turn("정회원 승격 조건은?", "총회의 동의가 필요합니다. [제7조]"));
        store.saveAll("7:abandoned", turn("탭을 열어만 두었다", "답"));

        clock.advance(Duration.ofHours(23));
        assertThat(store.findByConversationId("7:asked")).as("아직 하루가 지나지 않았다").hasSize(2);

        clock.advance(Duration.ofHours(2));

        assertThat(store.findByConversationId("7:abandoned")).as("25시간 동안 아무도 쓰지 않았다").isEmpty();
        assertThat(store.findByConversationId("7:asked")).as("23시간째에 읽혔으므로 그때부터 다시 하루다").hasSize(2);
    }

    /*
     * **슬라이딩이다**(`expireAfterAccess`) — 이어 가고 있는 대화는 24시간이 지났다고 한가운데서
     * 사라지지 않는다. 고정 만료였다면 사용자에게는 **방금 물어본 것을 도우미가 잊은 것**으로
     * 보인다.
     */
    @Test
    void startsTheDayOverEveryTimeTheConversationIsUsed() {
        AssistantMemoryStore store = store(500, DAY);
        store.saveAll("7:abc", turn("정회원 승격 조건은?", "총회의 동의가 필요합니다. [제7조]"));

        for (int hop = 0; hop < 5; hop++) {
            clock.advance(Duration.ofHours(20));
            assertThat(store.findByConversationId("7:abc"))
                    .as("마지막 접근으로부터 20시간 — 창이 그때마다 다시 열린다")
                    .hasSize(2);
        }

        clock.advance(DAY.plusMinutes(1));
        assertThat(store.findByConversationId("7:abc")).as("쓰지 않은 채 하루가 지나면 사라진다").isEmpty();
    }

    /*
     * **만료된 대화가 목록에 남아 있지 않다.**
     *
     * Caffeine은 만료를 지연 정리하므로(§7.3) 그냥 훑으면 «조회하면 비어 있는데 목록에는 있는»
     * 키가 섞인다 — 두 메서드가 서로 다른 사실을 말하게 되는 자리다.
     */
    @Test
    void doesNotListConversationsThatHaveAlreadyExpired() {
        AssistantMemoryStore store = store(500, DAY);
        store.saveAll("7:abc", turn("질문", "답"));
        store.saveAll("9:def", turn("질문", "답"));

        clock.advance(DAY.plusMinutes(1));

        assertThat(store.findConversationIds()).isEmpty();
    }

    // ------------------------------------------------------------------ 네 메서드의 계약

    /* 한 번도 묻지 않은 대화는 **빈 목록이지 null이 아니다** — 만료와 구별하지 않는다 */
    @Test
    void answersWithAnEmptyHistoryForAConversationNobodyOpened() {
        assertThat(store(500, DAY).findByConversationId("7:none")).isEmpty();
    }

    /* 초기화 — 지운 대화는 빈 이력으로 돌아온다(패널의 `↺`가 닿는 자리) */
    @Test
    void forgetsAConversationOnDelete() {
        AssistantMemoryStore store = store(500, DAY);
        store.saveAll("7:abc", turn("질문", "답"));

        store.deleteByConversationId("7:abc");

        assertThat(store.findByConversationId("7:abc")).isEmpty();
        assertThat(store.findConversationIds()).isEmpty();
    }

    /*
     * **담을 때 복사한다.** 부르는 쪽이 들고 있던 목록을 나중에 손대면 캐시 안의 값이 함께
     * 바뀌는데, 그 목록은 **다음 턴의 프롬프트가 되는 값**이다.
     */
    @Test
    void copiesTheHistorySoLaterChangesDoNotLeakIn() {
        AssistantMemoryStore store = store(500, DAY);
        List<Message> messages = new ArrayList<>(turn("질문", "답"));

        store.saveAll("7:abc", messages);
        messages.add(new UserMessage("나중에 끼워 넣은 질문"));

        assertThat(store.findByConversationId("7:abc")).hasSize(2);
    }

    /* 대화는 서로 섞이지 않는다 — 키가 곧 «누구의 어느 탭인가»다(§7.4) */
    @Test
    void keepsConversationsApart() {
        AssistantMemoryStore store = store(500, DAY);
        store.saveAll("7:abc", turn("7번의 질문", "답"));
        store.saveAll("9:abc", turn("9번의 질문", "답"));

        assertThat(store.findByConversationId("7:abc").get(0).getText()).isEqualTo("7번의 질문");
        assertThat(store.findByConversationId("9:abc").get(0).getText()).isEqualTo("9번의 질문");
    }

    // ------------------------------------------------------------------ 픽스처

    private AssistantMemoryStore store(long maxConversations, Duration ttl) {
        return new AssistantMemoryStore(maxConversations, ttl, clock);
    }

    private List<Message> turn(String question, String answer) {
        return List.of(new UserMessage(question), new AssistantMessage(answer));
    }

    /*
     * 옮길 수 있는 시계 — `Clock.fixed`로는 만료의 순간을 만들 수 없다
     * (`AssistantRateLimiterTest`와 같은 자리이며, 그쪽은 창 번호를, 여기서는 Caffeine의
     * `ticker`를 이 시계에 물린다).
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
