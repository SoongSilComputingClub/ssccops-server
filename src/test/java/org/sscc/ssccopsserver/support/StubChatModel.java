package org.sscc.ssccopsserver.support;

import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 테스트용 채팅 모델 (#403).
 *
 * <p><b>Gemini를 부르지 않고 «무엇을 넘겼고 몇 번 불렀는가»를 본다.</b> 규정 도우미에서 가장 중요한 동작이 거절이고(기획안 §6.1) 그 정의가 «모델을
 * 부르지 않는다»라, <b>부른 횟수 자체가 검증 대상</b>이다. 실제 모델 품질은 골든셋(#405)이 실제 스택에서 본다 — 외부 의존이고 비결정적이라 CI에 넣지 않는다.
 *
 * <p>스프링 컨텍스트를 나눠 쓰는 스텁이라 <b>테스트 사이에 상태가 샌다</b> — {@link #reset()}을 {@code @BeforeEach}에서 부를 것
 * ({@code InMemoryRagChunkStore.clear()}와 같은 자리).
 */
public class StubChatModel implements ChatModel {

    /** 다음 호출이 돌려줄 답. 인용 표기를 담아 두면 검증기가 그것을 실제 청크와 대조한다 */
    private String answer = "규정에 따르면 그렇습니다. [제7조]";

    /** 값을 넣어 두면 그 예외를 던진다 — 공급자 장애·타임아웃 경로(503)를 이것으로 본다 */
    private RuntimeException failure;

    private int calls;

    private Prompt lastPrompt;

    @Override
    public ChatResponse call(Prompt prompt) {
        this.lastPrompt = prompt;
        this.calls++;
        if (failure != null) {
            throw failure;
        }
        return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
    }

    public void answerWith(String answer) {
        this.answer = answer;
    }

    public void failWith(RuntimeException failure) {
        this.failure = failure;
    }

    /** 몇 번 불렸나 — <b>0이 곧 «근거가 없어 부르지 않았다»의 증거다</b> */
    public int calls() {
        return calls;
    }

    /** 마지막으로 넘긴 프롬프트. 발췌·질문 블록과 «회원 정보가 없다»를 이것으로 본다(§11) */
    public Prompt lastPrompt() {
        return lastPrompt;
    }

    public void reset() {
        answer = "규정에 따르면 그렇습니다. [제7조]";
        failure = null;
        calls = 0;
        lastPrompt = null;
    }
}
