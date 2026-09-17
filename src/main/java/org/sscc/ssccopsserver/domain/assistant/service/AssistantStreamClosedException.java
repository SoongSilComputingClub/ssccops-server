package org.sscc.ssccopsserver.domain.assistant.service;

/**
 * 보내는 쪽이 닫혔다 — <b>더 만들 이유가 없다</b> (#447).
 *
 * <p>{@link AssistantAnswerSink#delta}가 이것을 던지면 모델 구독이 취소된다. 사용자가 탭을 닫았거나 응답이 타임아웃된 자리이며, 그때까지도 생성을
 * 끝까지 돌리면 <b>아무도 읽지 않는 답에 무료 쿼터를 쓴다</b>(§11 — 쿼터는 API 키 단위의 공유 자원이다).
 *
 * <p><b>공급자 장애와 갈린다.</b> 그쪽은 {@code ASSISTANT_UPSTREAM_FAILED} 오류 이벤트로 알려야 하지만, 이쪽은 알릴 상대가 이미 없으므로
 * 서비스가 로그 한 줄만 남기고 조용히 끝낸다.
 */
public class AssistantStreamClosedException extends RuntimeException {

    public AssistantStreamClosedException() {
        super("규정 도우미 응답 스트림이 닫혔다");
    }
}
