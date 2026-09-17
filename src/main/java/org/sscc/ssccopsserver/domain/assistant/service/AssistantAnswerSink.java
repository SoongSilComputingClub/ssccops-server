package org.sscc.ssccopsserver.domain.assistant.service;

import org.sscc.ssccopsserver.domain.assistant.dto.AssistantQueryResponse;
import org.sscc.ssccopsserver.global.apipayload.code.error.ErrorCode;

/**
 * 흘러나오는 답을 받는 자리 (#447).
 *
 * <p><b>서비스가 전송을 모르게 하려고 있는 인터페이스다.</b> 구현은 컨트롤러가 {@code SseEmitter} 위에 만든다 — 서비스가 그 타입을 직접 들면 «규정
 * 도우미를 부르려면 서블릿 응답이 있어야 한다»가 되어 골든셋·단위 테스트가 웹 계층을 함께 세워야 한다.
 *
 * <h2>부르는 순서</h2>
 *
 * <p>{@code delta}가 0회 이상, 그 뒤에 <b>{@code done} 또는 {@code failed} 중 하나가 정확히 한 번</b>. 거절은 {@code
 * delta} 없이 {@code done} 하나다 — 근거가 없으면 모델을 부르지 않으므로 흘려보낼 글자가 애초에 없다.
 *
 * <h2>어디까지가 이 인터페이스의 몫이 아닌가</h2>
 *
 * <p><b>첫 바이트 전의 거절은 여기로 오지 않는다.</b> 기능 플래그(404) · 질문 길이(413) · 배선 없음(503) · 남의 대화(403) · 한도(429)는
 * {@link AssistantService#queryStreaming}이 <b>돌아오기 전에</b> 예외로 던지고, 그것을 {@code
 * GlobalExceptionHandler}가 평소의 {@code ApiResponse} 봉투로 받는다. 그 순서가 지켜져야 «SSE 로 바꾸면서 거절의 계단이 흐려지지
 * 않는다»가 성립한다.
 *
 * <p>{@code failed}로 오는 것은 <b>이미 스트림이 열린 뒤의 실패</b>뿐이다 — 상태 코드를 바꿀 수 없는 자리라 오류를 이벤트로 내려야 한다.
 */
public interface AssistantAnswerSink {

    /**
     * 본문 조각 하나 — <b>여기 오는 글자는 이미 인용 판정을 지났다</b>({@code CitationVerifier.Session}).
     *
     * <p>버려질 토큰과 그 앞의 공백은 이 자리에 닿기 전에 빠진다. 흘려보낸 글자는 되돌릴 수 없으므로 «보내고 나서 고친다»는 길이 없다.
     */
    void delta(String text);

    /** 다 만들었다 — 답과 인용·판본이 확정됐다. 이 뒤로는 아무것도 오지 않는다 */
    void done(AssistantQueryResponse response);

    /** 흘려보내는 도중에 실패했다 — 상태 코드를 바꿀 수 없으므로 오류 이벤트로 나간다 */
    void failed(ErrorCode errorCode);
}
