package org.sscc.ssccopsserver.domain.assistant.service;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Scheduler;

import lombok.extern.slf4j.Slf4j;

/*
 * 대화 이력이 사는 곳 — 애플리케이션 힙의 Caffeine 캐시 (#406 · 기획안 §7 · §8.1).
 *
 * ══ 왜 별도 저장소를 세우지 않는가 ══════════════════════════════
 *
 * 담는 것이 **대화 500개 × 20턴 ≈ 12MB**이고, 여기에 질의 임베딩 캐시 3.3MB와 레이트 리밋
 * 카운터 15KB를 더해도 **약 15MB**다(§8.1). 배포 호스트가 Coolify(13.6GB)이고 컨테이너 메모리
 * 제한을 걸지 않았으므로(#202에서 JVM 플래그를 걷어냈다) 그 0.5% 미만이며, 부팅 때 Hibernate가
 * 엔티티 41종의 메타모델을 만드는 데 쓰는 양이 이보다 훨씬 크다.
 *
 * Redis를 들이지 않은 것은 그 한 벌을 위해 **운영할 배포 단위가 느는 쪽이 비대칭**이기
 * 때문이다(ADR-0028 · `AssistantRateLimiter`와 같은 판단). 옮길 때 필요한 것이 그대로 대응된다 —
 * `EXPIRE`는 `expireAfterWrite`, 슬라이딩 갱신은 `expireAfterAccess`, `maxmemory +
 * allkeys-lru`는 `maximumSize`다.
 *
 * ══ 언제 외부 저장소로 바꾸나 — 기준을 미리 정해 둔다 ═══════════
 *
 *   ① **인스턴스를 둘 이상 띄울 때.** 힙이 인스턴스마다 갈려 같은 사람의 두 질문이 서로 다른
 *      대화를 보게 된다 — `MemberLinkAttemptLimiter`·`AssistantRateLimiter`·색인 워커의 부팅
 *      복구가 이미 안고 있는 자리와 같다(ssccops#324의 마지막 칸). **그때는 이 절과 그 셋을
 *      함께 본다.**
 *   ② **「어제 물어본 것 이어서」가 실제 요구로 올라올 때.** 그건 캐시가 아니라 **제품 데이터**라
 *      Redis가 아니라 RDB로 간다 — 그 순간 질의 로그를 두지 않기로 한 판단(§9)도 함께 다시 봐야
 *      한다. 지금 이 캐시는 «세션이 살아 있는 동안의 맥락»이지 기록이 아니다.
 *
 * 둘 다 아니면 힙에 둔다. 바꾸는 비용도 작다 — {@link ChatMemoryRepository} 구현을 갈아 끼우는
 * 것이라 부르는 쪽(`AssistantConversations`)이 바뀌지 않는다.
 *
 * ══ Spring AI의 `InMemoryChatMemoryRepository`를 그대로 쓰지 않는다 ══
 *
 * ⚠️ **그쪽에는 천장이 없다.** `conversationId`마다 항목을 쌓고 아무것도 꺼내지 않으며,
 * `MessageWindowChatMemory`가 제한하는 것은 *대화 하나의 턴 수*이지 *대화의 개수*가 아니다.
 * 그 빈은 자동 구성(`ChatMemoryAutoConfiguration`)이 `@ConditionalOnMissingBean`으로 만들고
 * 있었으므로 — 즉 **지금까지도 컨텍스트에 상한 없는 맵이 하나 서 있었다** — 이 클래스가 빈으로
 * 서면서 그쪽이 물러난다. 그 사실은 `AssistantWiringTest`가 본다.
 *
 * ══ 상한이 메모리를 보증한다 — TTL이 아니다 ═════════════════════
 *
 * ⚠️ **Caffeine도 Redis도 만료를 지연 정리한다.** 만료된 항목은 조회하면 없는 것으로 보이지만
 * (정확성은 보장된다) **메모리 회수는 다음 캐시 연산 때** 일어난다. 그래서 15MB를 실질적으로
 * 지키는 것은 `maximumSize`이고, TTL만 걸고 상한을 빼면 **만료 전 항목이 몰릴 때 천장이 없다.**
 * 즉시 회수를 위해 `Scheduler.systemScheduler()`를 함께 건다.
 *
 * **세 가지가 이 숫자를 지킨다**(§8.1): 여기의 `maximumSize`·턴 상한
 * (`AssistantConversations`) · **답변 3~5문장 규칙**(`AssistantPrompt`의 다섯째 · 답이 길어지면
 * 메시지당 600B가 커진다 — 품질 규칙이 용량 규칙을 겸한다) · 질문 1,000자 상한
 * (`AssistantQueryPolicy`). 그 셋 중 하나를 움직이면 이 절의 산수가 함께 움직인다.
 *
 * ══ 시계는 주입받는다 ══════════════════════════════════════════
 *
 * Caffeine의 `ticker`에 이 레포가 이미 주입받는 `Clock`을 물린다(ClockConfig · AP-12). **만료
 * 자체를 테스트할 수 있는 것이 그 덕이며**, 외부 저장소보다 오히려 쉬운 자리다
 * (`AssistantMemoryStoreTest`). 그 시계가 단조 시계가 아니라 **벽시계**라 NTP 보정이 만료를 몇 초
 * 당기거나 미룰 수 있는데, 24시간 창에서 그것은 아무 뜻도 없는 오차다 — 잃는 것보다 얻는 것이
 * 크다.
 *
 * ══ 무엇이 들어가지 않는가 ══════════════════════════════════════
 *
 * **문서 발췌가 들어가지 않는다.** 저장되는 것은 사용자의 질문과 우리가 내보낸 답변뿐이며,
 * 프롬프트에 실린 발췌 블록은 매 턴 검색해서 새로 만든다 — 발췌까지 담으면 메시지 하나가
 * 600B가 아니라 8KB가 되어 §8.1의 산수가 스무 배로 틀리고, 다음 턴마다 그것을 모델에 다시
 * 보내게 된다. 무엇을 담을지 정하는 자리는 `AssistantConversations.remember` 한 곳이다.
 */
@Slf4j
@Component
public class AssistantMemoryStore implements ChatMemoryRepository {

    private final Cache<String, List<Message>> conversations;

    public AssistantMemoryStore(
            @Value("${ssccops.assistant.memory.max-conversations:500}") long maxConversations,
            @Value("${ssccops.assistant.memory.ttl:PT24H}") Duration ttl,
            Clock clock) {

        this.conversations =
                Caffeine.newBuilder()
                        /*
                         * **이 줄이 §8.1의 12MB를 보증한다.** 올리는 것은 힙 예산을 올리는
                         * 것이므로 그 절의 산수를 함께 고친다.
                         */
                        .maximumSize(maxConversations)
                        /*
                         * **슬라이딩이다**(`expireAfterWrite`가 아니다) — 이어 가고 있는 대화가
                         * 24시간이 지났다고 한가운데서 사라지면, 사용자에게는 방금 물어본 것을
                         * 도우미가 잊은 것으로 보인다.
                         */
                        .expireAfterAccess(ttl)
                        .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis()))
                        // 만료된 항목의 메모리를 다음 캐시 연산까지 들고 있지 않는다(위 ⚠️)
                        .scheduler(Scheduler.systemScheduler())
                        .build();

        // 값을 그대로 찍는다 — 「시간」으로 옮겨 적으면 분 단위로 줄여 둔 설정이 «0시간»으로 보인다
        log.info("규정 도우미 대화 메모리 — 대화 {}개 · 마지막 접근 후 {}", maxConversations, ttl);
    }

    /**
     * 그 대화의 이력 — <b>만료됐으면 빈 목록이고 그것이 정상이다</b>.
     *
     * <p>24시간 슬라이딩 만료는 설계된 동작이므로 «없는 대화»를 오류로 만들지 않는다. 화면은 그때 새 대화처럼 보이며(ssccops-web#434) 서버가 그 사실을
     * 알려 줄 자리도 없다 — 대화가 있었는지 자체가 이 캐시에만 있던 값이다.
     */
    @Override
    public List<Message> findByConversationId(String conversationId) {
        List<Message> found = conversations.getIfPresent(conversationId);
        return found == null ? List.of() : found;
    }

    /**
     * 그 대화의 이력을 통째로 바꾼다 — {@link ChatMemory}가 턴 상한까지 적용한 뒤 넘겨 준다.
     *
     * <p><b>복사해서 담는다.</b> 부르는 쪽이 들고 있던 목록이 나중에 바뀌면 캐시 안의 값이 함께 바뀌는데, 그 목록은 다음 턴의 프롬프트가 되는 값이다.
     */
    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        conversations.put(conversationId, List.copyOf(messages));
    }

    /** 대화를 지운다 — 패널의 {@code ↺}가 닿는 자리다. 없는 대화를 지우는 것도 성공이다 */
    @Override
    public void deleteByConversationId(String conversationId) {
        conversations.invalidate(conversationId);
    }

    /**
     * 살아 있는 대화의 키 전부.
     *
     * <p><b>우리는 부르지 않는다</b> — {@link ChatMemoryRepository}가 요구해서 채운다. 돌려주는 값에 <b>다른 회원의 대화 키가 섞여
     * 있으므로</b> API로 내보내지 말 것(키 앞부분이 곧 회원 식별자다).
     *
     * <p>먼저 {@code cleanUp()}을 도는 것은 Caffeine이 만료를 지연 정리하기 때문이다 — 그냥 훑으면 «조회하면 비어 있는데 목록에는 있는» 키가
     * 섞인다. 이 메서드가 테스트에서 상한·만료를 재는 창이기도 하다.
     */
    @Override
    public List<String> findConversationIds() {
        conversations.cleanUp();
        return List.copyOf(conversations.asMap().keySet());
    }
}
