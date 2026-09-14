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

    /*
     * 429 — 적재 한도를 넘었다 (#399 · 기획안 §11).
     *
     * **적재에 별도 한도를 두는 것은 그 요청 하나가 나중에 임베딩을 수백 번 부르기 때문이다** —
     * 1.2MB PDF 한 건이 184청크이고, 무료 쿼터는 API 키 단위의 공유 자원이라 한 사람이 태우면
     * 모두가 답을 못 받는다. 공급자의 429가 아니라 **우리 코드가 먼저 끊어 우리 문구로 안내**하는
     * 자리이며, 질의 한도(#404)도 같은 코드를 쓴다 — 화면이 할 안내가 «잠시 뒤 다시»로 같다.
     */
    ASSISTANT_RATE_LIMITED(
            HttpStatus.TOO_MANY_REQUESTS, "ASSISTANT_RATE_LIMITED", "요청 한도를 초과했습니다."),

    /*
     * 400 — `.md`·`.pdf`·`.docx` 밖의 확장자다 (#399).
     *
     * **파싱 실패(아래)와 나눈다.** 코드를 하나로 합치면 화면이 «파일을 고쳐 다시 올리세요»
     * 하나만 말하게 되는데, 운영진이 할 일은 한쪽이 «형식을 바꾼다»이고 다른 쪽은 «내용을
     * 고친다»라 서로 겹치지 않는다. 판정하는 자리는 확장자 표(`RagDocumentFormat`) 한 곳이다.
     */
    RAG_DOCUMENT_UNSUPPORTED_TYPE(
            HttpStatus.BAD_REQUEST, "RAG_DOCUMENT_UNSUPPORTED_TYPE", "받지 않는 파일 형식입니다."),

    /*
     * 413 — 업로드 파일이 10MB를 넘었다 (#399).
     *
     * **서블릿 상한(`spring.servlet.multipart.max-file-size` 16MB)이 아니라 도메인이 끊는다** —
     * 서블릿 계층이 먼저 걸러 버리면 도메인 오류 코드가 붙지 않은 응답이 나가고 화면이 무엇이
     * 잘못됐는지 안내하지 못한다(#84가 CSV 5MB에서 쓴 두 겹 그대로). 화면도 올리기 전에 한 번
     * 막지만 **서버 판정이 방어선이라 둘 중 하나를 없애지 않는다**(기획안 §13.2).
     */
    RAG_DOCUMENT_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "RAG_DOCUMENT_TOO_LARGE", "파일이 너무 큽니다."),

    /*
     * 400 — 파일이 계약을 어겨 파싱할 수 없다 (#397 · 기획안 §5.3).
     *
     * **사유마다 코드를 만들지 않는다.** 조가 하나도 없는지 · 장 없이 조가 시작하는지 ·
     * 조번호가 겹치는지는 «몇째 줄이 왜 걸렸는가»까지 담아야 쓸모가 있는데 그것은 코드가 아니라
     * 값이고, 코드를 늘려도 화면은 그 전부에 같은 안내(«파일을 고쳐 다시 올리세요»)를 반복한다 —
     * 그래서 사유는 `GeneralException.detail`에 싣는다(#150이 기획안 이관에서 정한 방식).
     *
     * `GENERIC` 쪽에서 텍스트가 한 글자도 추출되지 않은 경우(스캔 PDF)도 같은 코드다(#398).
     */
    RAG_DOCUMENT_PARSE_FAILED(
            HttpStatus.BAD_REQUEST, "RAG_DOCUMENT_PARSE_FAILED", "문서를 읽을 수 없습니다."),

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
