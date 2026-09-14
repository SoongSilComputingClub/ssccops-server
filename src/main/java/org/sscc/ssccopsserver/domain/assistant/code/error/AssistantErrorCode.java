package org.sscc.ssccopsserver.domain.assistant.code.error;

import org.springframework.http.HttpStatus;
import org.sscc.ssccopsserver.global.apipayload.code.error.ErrorCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

/*
 * 규정 도우미 도메인 전용 에러 코드 (#396 · Epic ssccops#321).
 *
 * **전역(`CommonErrorCode`)이 아니다.** 여기 있는 것은 전부 «규정 도우미가 어떤 상태인가»를
 * 말하는 값이라 다른 도메인에서 던질 자리가 없다.
 *
 * 코드 문자열은 다른 도메인과 같은 영문 UPPER_SNAKE_CASE다. 이 enum은 기능이 자라며 늘어난다 —
 * 업로드·파싱(#399)·질의·레이트 리밋(#404)이 각자의 값을 더한다.
 */
@Getter
@AllArgsConstructor
public enum AssistantErrorCode implements ErrorCode {

    /*
     * 404 — 기능 플래그(`ssccops.assistant.enabled`)가 꺼져 있다.
     *
     * **없는 자원의 404와 코드를 나눈다**(회원 하드 삭제의 `FEATURE_DISABLED`와 같은 판단 · #361).
     * 웹의 플래그와 서버의 플래그가 갈렸을 때 이것이 `NOT_FOUND`로 오면 화면은 «문서가 사라졌다»로
     * 읽는다. 403이 아닌 것은 권한 문제가 아니기 때문이다 — 그 사람에게 권한이 있어도 답은 같다.
     */
    ASSISTANT_DISABLED(HttpStatus.NOT_FOUND, "ASSISTANT_DISABLED", "규정 도우미가 비활성화되어 있습니다."),

    /*
     * 503 — 모델·벡터 저장소 배선이 서 있지 않다.
     *
     * Gemini API 키가 없으면 임베딩 모델 빈이 없고, 그러면 `VectorStore`도 없다
     * (`GeminiWiringEnvironmentPostProcessor`). 그 상태는 «설정이 덜 된 서버»이지 요청의 잘못이
     * 아니므로 4xx가 아니다. **플래그 off(404)와 갈린다** — 이쪽은 켜 두고 키를 넣지 않은 상태라
     * 운영자가 할 일이 있다.
     */
    ASSISTANT_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE, "ASSISTANT_UNAVAILABLE", "규정 도우미를 사용할 수 없습니다."),

    /** 400 — 색인 상태 전이표(`RagIndexStatus.canTransitionTo`)를 어겼다 */
    INVALID_RAG_INDEX_STATUS_TRANSITION(
            HttpStatus.BAD_REQUEST, "INVALID_RAG_INDEX_STATUS_TRANSITION", "색인 상태를 그렇게 바꿀 수 없습니다."),

    /** 400 — 적용 상태 전이표(`RagApplyStatus.canTransitionTo`)를 어겼다 */
    INVALID_RAG_APPLY_STATUS_TRANSITION(
            HttpStatus.BAD_REQUEST, "INVALID_RAG_APPLY_STATUS_TRANSITION", "적용 상태를 그렇게 바꿀 수 없습니다."),

    /*
     * 409 — 색인이 끝나지 않은 판본을 시행 중으로 올리려 했다.
     *
     * 통과시키면 «시행 중인데 검색되지 않는 문서»가 되어 도우미가 근거 없이 침묵한다 — 화면에는
     * 반영됐다고 뜨는데 답변만 달라지지 않는, 아무도 원인을 찾지 못하는 종류의 고장이다.
     */
    RAG_DOCUMENT_NOT_INDEXED(
            HttpStatus.CONFLICT, "RAG_DOCUMENT_NOT_INDEXED", "색인이 끝난 판본만 시행 중으로 올릴 수 있습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
