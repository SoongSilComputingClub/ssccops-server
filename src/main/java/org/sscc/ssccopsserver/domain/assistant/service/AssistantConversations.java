package org.sscc.ssccopsserver.domain.assistant.service;

import java.util.List;
import java.util.UUID;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.assistant.code.error.AssistantErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;

/*
 * 대화 — 식별자를 발급하고, 이력을 꺼내고, 한 턴을 담는다 (#406 · 기획안 §7).
 *
 * ══ `conversationId`를 서버가 만든다 ════════════════════════════
 *
 * **`{회원 식별자}:{탭 UUID}`**이고 **클라이언트가 보낸 값을 그대로 키로 쓰지 않는다** — 그러면
 * 남의 식별자를 넣어 **남의 대화를 읽을 수 있다**(§7.4). 힙에 둔다고 이 규칙이 느슨해지지
 * 않으며, 경로에 `mbrId`를 두지 않는 이유(§10 · 대상은 언제나 인증 주체 본인)와 같은 자리다.
 *
 * 앞부분이 내 것이 아니거나 모양이 다르면 403 {@code ASSISTANT_CONVERSATION_FORBIDDEN}이다.
 * 둘을 가르지 않는 이유는 그 코드의 주석에 있다. **요청이 비워 보내면 새 대화를 연다** — 첫
 * 질문에는 들고 있을 값이 없고, 화면은 응답으로 받은 값을 그대로 다음 질문에 싣는다.
 *
 * ⚠️ **식별자가 있다는 것이 «이력이 있다»는 뜻은 아니다.** 24시간 슬라이딩 만료(§7.2)가 지난
 * 대화는 **빈 이력**으로 돌아오며 그것이 설계된 동작이다 — 오류로 만들지 않는 것은 화면이 그때
 * 새 대화처럼 그려야 하기 때문이다(ssccops-web#434).
 *
 * ══ 무엇을 담는가 — 질문과 답변뿐이다 ═══════════════════════════
 *
 * **문서 발췌는 담지 않는다.** 모델에게 보내는 사용자 메시지에는 발췌 블록이 붙지만
 * (`AssistantPrompt.user`), 여기 남는 것은 **사람이 친 질문**과 **검증을 통과한 답변**뿐이다.
 * 발췌까지 담으면 ① §8.1의 «메시지 하나 600B»가 8KB로 스무 배 틀리고 ② 다음 턴마다 지난
 * 발췌를 모델에 다시 보내 무료 쿼터를 두 배로 태운다. 발췌는 매 턴 검색으로 새로 만든다.
 *
 * **거절은 담지 않는다.** 「찾지 못했습니다」는 이어 갈 맥락이 아니고, 담으면 20턴 창을 차지하는
 * 데다 모델에게 «이 대화에서는 이렇게 답한다»는 본보기가 된다. 429·413·503으로 끝난 요청도
 * 마찬가지로 아무것도 남기지 않는다 — 담는 자리는 {@link #remember} 하나다.
 *
 * ══ 턴 상한은 `MessageWindowChatMemory`가 건다 ══════════════════
 *
 * 대화 **하나**의 길이를 자르는 것이 그쪽이고(20턴 = 메시지 40개), 대화 **개수**의 천장은
 * `AssistantMemoryStore`의 `maximumSize`다. **둘이 함께 있어야 §8.1의 12MB가 성립한다** —
 * Spring AI의 기본 배선은 앞의 것만 있고 뒤의 것이 없다.
 *
 * ══ 검색어는 이번 질문 하나다 ═══════════════════════════════════
 *
 * 대화는 **답변 생성의 맥락**이지 검색의 재료가 아니다. 이전 질문을 검색어에 이어 붙이는 안은
 * 택하지 않았다 — 임베딩이 두 주제 사이로 끌려가 **맞는 청크가 임계값 아래로 내려가는데**, 그
 * 실패가 «근거를 찾지 못했다»로만 보여 아무도 원인을 찾지 못한다. 모델로 질문을 다시 쓰는 안은
 * 질의 한 건의 Gemini 호출을 둘에서 셋으로 늘린다(레이트 리밋이 «두 번»을 전제로 잡힌 값이다).
 * 그래서 이어 말한 질문이 홀로 서지 못하면 거절되며, **그 거절이 안전한 실패다**(§6.1).
 * 필요가 실제로 확인되면 골든셋(§14.2)에 그 질문을 넣고 재 본 뒤에 손댄다.
 */
@Component
@RequiredArgsConstructor
public class AssistantConversations {

    /** 서버가 발급한 값만 통과한다 — {@code {회원 식별자}:{탭 UUID}} */
    private static final String SEPARATOR = ":";

    private final ChatMemory memory;

    /**
     * 이어 갈 대화를 정한다 — 요청이 비었으면 새로 발급하고, 보냈으면 <b>내 것인지 본다</b>.
     *
     * @param requested 화면이 앞선 응답에서 받아 들고 있던 값. {@code null}·공백이면 새 대화다
     * @return 이 질의가 쓸 대화 식별자. 응답에 그대로 실려 화면이 다음 질문에 싣는다
     * @throws GeneralException 403 {@code ASSISTANT_CONVERSATION_FORBIDDEN} — 남의 것이거나 서버가 발급하지 않은
     *     모양
     */
    public String open(long memberId, String requested) {
        if (requested == null || requested.isBlank()) {
            return memberId + SEPARATOR + UUID.randomUUID();
        }
        return requireOwned(memberId, requested);
    }

    /** 앞선 턴들 — <b>만료됐으면 빈 목록이고 그것이 정상이다</b>(위 ⚠️) */
    public List<Message> history(String conversationId) {
        return memory.get(conversationId);
    }

    /**
     * 한 턴을 담는다 — <b>답한 질의에서만 부른다</b>.
     *
     * <p>담기는 것은 사람이 친 질문과 검증을 통과한 답변 둘뿐이다(클래스 주석). 턴 상한을 넘으면 {@code MessageWindowChatMemory}가 오래된
     * 것부터 덜어낸다.
     */
    public void remember(String conversationId, String question, String answer) {
        memory.add(
                conversationId, List.of(new UserMessage(question), new AssistantMessage(answer)));
    }

    /**
     * 대화를 지운다 — 패널의 {@code ↺}가 닿는 자리다.
     *
     * <p><b>없는 대화를 지우는 것도 성공이다.</b> 만료됐거나 아직 한 번도 묻지 않은 식별자를 구별해 줄 값이 서버에 없고, 화면이 할 일이 «처음 화면으로
     * 되돌린다»로 같다. 대신 <b>남의 것인지는 본다</b> — 지우는 것도 남의 대화에 닿는 일이다.
     */
    public void clear(long memberId, String conversationId) {
        memory.clear(requireOwned(memberId, conversationId));
    }

    /*
     * 서버가 발급한 모양인가, 그리고 이 사람의 것인가. **둘을 한 번에 본다** — 나누면 «모양은
     * 맞는데 남의 것»과 «내 것인데 모양이 틀린 것»에 서로 다른 응답을 주게 되고, 그 차이는
     * 남의 식별자를 넣어 보는 쪽에게만 쓸모가 있다.
     */
    private String requireOwned(long memberId, String conversationId) {
        String prefix = memberId + SEPARATOR;
        if (!conversationId.startsWith(prefix)
                || !isUuid(conversationId.substring(prefix.length()))) {
            throw new GeneralException(AssistantErrorCode.ASSISTANT_CONVERSATION_FORBIDDEN);
        }
        return conversationId;
    }

    /*
     * **표준 표기 그대로여야 한다.** `UUID.fromString`은 `1-1-1-1-1`처럼 짧은 값도 대문자 표기도
     * 받아 주는데, 통과시키면 ① 발급한 적 없는 모양이 키가 되고 ② 같은 대화가 표기에 따라 두
     * 키로 갈려 «이어 물었는데 이력이 없는» 상태가 된다. 되돌려 견주는 한 줄이 그 둘을 함께 막는다.
     */
    private boolean isUuid(String candidate) {
        try {
            return UUID.fromString(candidate).toString().equals(candidate);
        } catch (IllegalArgumentException notAUuid) {
            return false;
        }
    }
}
