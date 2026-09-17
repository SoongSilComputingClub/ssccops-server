package org.sscc.ssccopsserver.global.mcp.client;

/*
 * 도구가 모델에게 돌려주는 오류 (#385 · ADR-0027).
 *
 * mcp-annotations는 도구 메서드가 던진 예외를 `CallToolResult(isError=true, text=근본 원인 메시지)`로
 * 바꾼다 — 그래서 **메시지가 곧 모델이 읽는 전부**다. REST가 준 `code`는 메시지 앞에 붙여 사람이
 * 로그와 대조할 수 있게 하고, 403·SIGNUP_REQUIRED는 «재시도해도 바뀌지 않는다»를 문장에 박는다.
 * 세 층의 403이 전부 `FORBIDDEN` 하나라(#118) 모델에게 줄 수 있는 것은 «권한 없음»뿐이고, 그 말이
 * 없으면 모델이 인자를 바꿔 가며 같은 호출을 되풀이한다(분석 문서 F6).
 */
public class McpToolException extends RuntimeException {

    private final String code;

    public McpToolException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
