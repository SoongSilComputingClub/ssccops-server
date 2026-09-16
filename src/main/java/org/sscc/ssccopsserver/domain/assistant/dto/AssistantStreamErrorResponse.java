package org.sscc.ssccopsserver.domain.assistant.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/*
 * SSE `error` 이벤트의 본문 — **첫 바이트 뒤에 일어난 실패** (#447).
 *
 * `GlobalExceptionHandler`가 잡지 못하는 자리다: 응답이 이미 200으로 나갔고 헤더도 보낸 뒤라
 * 상태 코드를 바꿀 수 없다. 그래서 오류를 **이벤트로** 내린다.
 *
 * **필드 이름이 `ApiResponse`의 오류와 같다**(`code`·`message`) — 화면이 «규정 도우미 오류»를
 * 두 가지 모양으로 읽지 않게 하기 위해서다. `success: false`를 싣지 않는 것은 이벤트 이름이 이미
 * 그 말을 하고 있기 때문이다.
 *
 * **첫 바이트 전의 거절은 여기로 오지 않는다** — 404·413·503·403·429는 평소대로 상태 코드와
 * `ApiResponse` 봉투로 나간다(`AssistantAnswerSink`).
 */
public record AssistantStreamErrorResponse(
        @Schema(
                        description = "오류 코드. ApiResponse 의 code 와 같은 어휘다",
                        example = "ASSISTANT_UPSTREAM_FAILED")
                String code,
        @Schema(description = "사람이 읽는 문장", example = "지금은 답변을 만들 수 없습니다.") String message) {}
