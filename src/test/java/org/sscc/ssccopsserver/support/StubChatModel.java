package org.sscc.ssccopsserver.support;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import reactor.core.publisher.Flux;

/**
 * 테스트용 채팅 모델 (#403 · #447).
 *
 * <p><b>Gemini를 부르지 않고 «무엇을 넘겼고 몇 번 불렀는가»를 본다.</b> 규정 도우미에서 가장 중요한 동작이 거절이고(기획안 §6.1) 그 정의가 «모델을
 * 부르지 않는다»라, <b>부른 횟수 자체가 검증 대상</b>이다. 실제 모델 품질은 골든셋(#405)이 실제 스택에서 본다 — 외부 의존이고 비결정적이라 CI에 넣지 않는다.
 *
 * <p><b>{@link #stream(Prompt)}은 답을 잘게 쪼개 흘려보낸다</b>(#447). 한 조각으로 주면 «토큰이 조각 경계에 걸쳐 온다»는 스트리밍의 실제
 * 조건을 한 번도 밟지 않는데, 인용 판정이 그 경계를 버텨야 하는 것이 이 기능의 요점이다({@code CitationVerifier.Session}). 그래서 기본
 * {@link #CHUNK}가 {@code [12]} 같은 토큰보다 짧다.
 *
 * <p>스프링 컨텍스트를 나눠 쓰는 스텁이라 <b>테스트 사이에 상태가 샌다</b> — {@link #reset()}을 {@code @BeforeEach}에서 부를 것
 * ({@code InMemoryRagChunkStore.clear()}와 같은 자리).
 */
public class StubChatModel implements ChatModel {

    /** 한 조각의 길이. <b>인용 토큰보다 짧아야</b> 조각 경계에 걸친 토큰이 실제로 만들어진다 */
    private static final int CHUNK = 3;

    private static final String DEFAULT_ANSWER = "규정에 따르면 그렇습니다. [1]";

    /** 다음 호출이 돌려줄 답. 발췌 번호를 담아 두면 해석기가 그것을 실제 발췌와 대조한다 */
    private String answer = DEFAULT_ANSWER;

    /** 값을 넣어 두면 그 예외를 던진다 — 공급자 장애·타임아웃 경로(503)를 이것으로 본다 */
    private RuntimeException failure;

    /** 스트리밍 전용 — <b>첫 조각을 내보낸 뒤</b> 실패한다. 상태 코드를 바꿀 수 없는 자리의 증인이다 */
    private RuntimeException failureAfterFirstDelta;

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

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        this.lastPrompt = prompt;
        this.calls++;
        if (failure != null) {
            return Flux.error(failure);
        }
        Flux<ChatResponse> pieces = Flux.fromIterable(pieces(answer)).map(StubChatModel::response);
        if (failureAfterFirstDelta == null) {
            return pieces;
        }
        return Flux.concat(pieces.take(1), Flux.error(failureAfterFirstDelta));
    }

    public void answerWith(String answer) {
        this.answer = answer;
    }

    public void failWith(RuntimeException failure) {
        this.failure = failure;
    }

    /** 흘려보내기 시작한 <b>뒤</b>의 실패 — 그때는 상태 코드가 아니라 오류 이벤트다(#447) */
    public void failStreamAfterFirstDelta(RuntimeException failure) {
        this.failureAfterFirstDelta = failure;
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
        answer = DEFAULT_ANSWER;
        failure = null;
        failureAfterFirstDelta = null;
        calls = 0;
        lastPrompt = null;
    }

    private static List<String> pieces(String text) {
        List<String> pieces = new ArrayList<>();
        for (int at = 0; at < text.length(); at += CHUNK) {
            pieces.add(text.substring(at, Math.min(text.length(), at + CHUNK)));
        }
        return pieces.isEmpty() ? List.of("") : pieces;
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
