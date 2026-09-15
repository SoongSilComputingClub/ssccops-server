package org.sscc.ssccopsserver.domain.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.sscc.ssccopsserver.domain.assistant.code.error.AssistantErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

/*
 * 대화 식별자의 규칙과 창 (#406 · 기획안 §7.1 · §7.4).
 *
 * ══ 무엇을 지키는가 ════════════════════════════════════════════
 *
 * **남의 대화를 읽지 못한다.** 힙에 둔다고 느슨해지지 않는 규칙이고, 여기가 그 판정이 사는
 * 유일한 자리다 — 통과하는 값이 «서버가 발급했고 지금 묻는 사람의 것»뿐임을 아래 넷이 본다.
 *
 * 나머지 둘은 **무엇을 담는가**(질문과 답변뿐)와 **얼마나 담는가**(20턴)다. 앞의 것이 §8.1의
 * «메시지 하나 600B»를, 뒤의 것이 «대화 하나 24KB»를 지킨다.
 *
 * 스프링을 띄우지 않는 것은 확인하려는 것이 배선이 아니라 규칙이기 때문이다 — 저장소는 진짜를
 * 쓴다(우리 것이고 가볍다).
 */
class AssistantConversationsTest {

    private static final long MEMBER = 7L;

    private static final int MAX_TURNS = 20;

    private final AssistantConversations conversations =
            new AssistantConversations(
                    MessageWindowChatMemory.builder()
                            .chatMemoryRepository(
                                    new AssistantMemoryStore(
                                            500,
                                            Duration.ofHours(24),
                                            Clock.fixed(
                                                    Instant.parse("2026-09-15T01:00:00Z"),
                                                    ZoneOffset.UTC)))
                            .maxMessages(MAX_TURNS * 2)
                            .build());

    // ------------------------------------------------------------------ 식별자는 서버가 만든다

    /* 첫 질문은 들고 있을 값이 없다 — 서버가 `{회원 식별자}:{탭 UUID}`로 발급한다 */
    @Test
    void issuesAnIdPrefixedWithTheAskingMember() {
        String issued = conversations.open(MEMBER, null);

        assertThat(issued).startsWith("7:");
        assertThat(UUID.fromString(issued.substring("7:".length()))).isNotNull();
        assertThat(conversations.open(MEMBER, "  ")).as("빈 값도 새 대화다").isNotEqualTo(issued);
    }

    /* 보낸 값이 내 것이면 그대로 이어 간다 — 그것이 «이어 말하기»의 전부다 */
    @Test
    void keepsTheIdWhenItIsTheCallersOwn() {
        String mine = conversations.open(MEMBER, null);

        assertThat(conversations.open(MEMBER, mine)).isEqualTo(mine);
    }

    /*
     * **남의 대화는 이어 갈 수 없다** (§7.4).
     *
     * 클라이언트가 보낸 값을 그대로 키로 쓰면 이 한 줄이 곧 **남의 대화 읽기**가 된다 — 힙에
     * 있든 Redis에 있든 같다.
     */
    @Test
    void refusesAConversationThatBelongsToSomeoneElse() {
        String someoneElses = conversations.open(9L, null);

        assertThatThrownBy(() -> conversations.open(MEMBER, someoneElses))
                .isInstanceOf(GeneralException.class)
                .extracting(thrown -> ((GeneralException) thrown).getErrorCode())
                .isEqualTo(AssistantErrorCode.ASSISTANT_CONVERSATION_FORBIDDEN);
    }

    /*
     * **서버가 발급하지 않은 모양도 같은 거절이다.** 통과하는 것은 발급한 모양 그대로뿐이며,
     * 그래야 «서버가 만든다»가 사실이 된다.
     *
     * `77:…`이 섞여 있는 것은 앞부분을 **숫자로** 견주지 않고 `7`로 시작하는지만 보면 통과해
     * 버리기 때문이다 — 회원 식별자가 세 자리를 넘는 순간 남의 대화가 열린다. 대문자 표기가
     * 섞여 있는 것은 `UUID.fromString`이 그것도 받아 주기 때문인데, 통과시키면 **같은 대화가
     * 표기에 따라 두 키로 갈려** «이어 물었는데 이력이 없는» 상태가 된다.
     */
    @Test
    void refusesAnythingTheServerDidNotIssue() {
        String uuid = UUID.randomUUID().toString();

        for (String forged :
                List.of(
                        "7",
                        "7:",
                        ":" + uuid,
                        "7:not-a-uuid",
                        "7:1-1-1-1-1",
                        "7:" + uuid.toUpperCase(),
                        "7:" + uuid + "-tail",
                        "77:" + uuid,
                        " 7:" + uuid)) {

            assertThatThrownBy(() -> conversations.open(MEMBER, forged))
                    .as(forged)
                    .isInstanceOf(GeneralException.class)
                    .hasMessageContaining("이어 갈 수 없습니다");
        }
    }

    // ------------------------------------------------------------------ 무엇을 담는가

    /* 담기는 것은 **사람이 친 질문과 우리가 내보낸 답변 둘**이다 — 발췌는 매 턴 새로 만든다 */
    @Test
    void remembersTheQuestionAndTheAnswerAndNothingElse() {
        String conversationId = conversations.open(MEMBER, null);

        conversations.remember(conversationId, "정회원 승격 조건은?", "총회의 동의가 필요합니다. [제7조]");

        List<Message> history = conversations.history(conversationId);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getMessageType()).isEqualTo(MessageType.USER);
        assertThat(history.get(0).getText()).isEqualTo("정회원 승격 조건은?");
        assertThat(history.get(1).getMessageType()).isEqualTo(MessageType.ASSISTANT);
        assertThat(history.get(1).getText()).isEqualTo("총회의 동의가 필요합니다. [제7조]");
    }

    /*
     * **대화 하나의 길이는 20턴에서 멈춘다** — §8.1이 «대화 1개 ≈ 24KB»를 셀 때 쓴 값이다.
     *
     * 대화의 *개수*를 막는 것은 이쪽이 아니라 `AssistantMemoryStore`의 `maximumSize`이고, 둘이
     * 함께 있어야 천장이 생긴다(Spring AI의 기본 배선에는 앞의 것만 있다).
     */
    @Test
    void keepsAtMostTwentyTurns() {
        String conversationId = conversations.open(MEMBER, null);

        for (int turn = 1; turn <= 25; turn++) {
            conversations.remember(
                    conversationId, "질문 %02d".formatted(turn), "답 %02d".formatted(turn));
        }

        List<Message> history = conversations.history(conversationId);
        assertThat(history).hasSize(MAX_TURNS * 2);
        assertThat(history.get(0).getText()).as("오래된 턴부터 덜어낸다").isEqualTo("질문 06");
        assertThat(history.get(history.size() - 1).getText()).isEqualTo("답 25");
    }

    /* 만료·초기화 뒤에도 **빈 이력이지 오류가 아니다** — 화면에는 새 대화처럼 보인다 */
    @Test
    void readsAnEmptyHistoryForAConversationThatHasNothingInIt() {
        assertThat(conversations.history(conversations.open(MEMBER, null))).isEmpty();
    }

    // ------------------------------------------------------------------ 초기화

    /* `↺` — 지우면 빈 이력이 되고, 같은 식별자로 계속 이어 물을 수 있다 */
    @Test
    void clearsTheHistoryButNotTheRightToKeepAsking() {
        String conversationId = conversations.open(MEMBER, null);
        conversations.remember(conversationId, "정회원 승격 조건은?", "총회의 동의가 필요합니다. [제7조]");

        conversations.clear(MEMBER, conversationId);

        assertThat(conversations.history(conversationId)).isEmpty();
        assertThat(conversations.open(MEMBER, conversationId)).isEqualTo(conversationId);
    }

    /* **없는 대화를 지우는 것도 성공이다** — 만료와 «아직 묻지 않음»을 가를 값이 서버에 없다 */
    @Test
    void clearingAConversationThatIsNotThereSucceeds() {
        assertThatCode(() -> conversations.clear(MEMBER, MEMBER + ":" + UUID.randomUUID()))
                .doesNotThrowAnyException();
    }

    /* **지우는 것도 남의 대화에 닿는 일이다** — 읽기와 같은 규칙을 쓴다 */
    @Test
    void refusesToClearSomeoneElsesConversation() {
        String someoneElses = conversations.open(9L, null);
        conversations.remember(someoneElses, "질문", "답");

        assertThatThrownBy(() -> conversations.clear(MEMBER, someoneElses))
                .isInstanceOf(GeneralException.class)
                .extracting(thrown -> ((GeneralException) thrown).getErrorCode())
                .isEqualTo(AssistantErrorCode.ASSISTANT_CONVERSATION_FORBIDDEN);

        assertThat(conversations.history(someoneElses)).as("남의 이력은 그대로다").hasSize(2);
    }
}
