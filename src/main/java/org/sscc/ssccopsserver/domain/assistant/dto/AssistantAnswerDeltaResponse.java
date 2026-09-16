package org.sscc.ssccopsserver.domain.assistant.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/*
 * SSE `delta` 이벤트의 본문 — 답변의 조각 하나 (#447).
 *
 * **문자열을 그대로 싣지 않고 객체에 담는다.** SSE 의 `data:` 는 줄 단위라 값 안의 줄바꿈이 여러
 * 줄로 갈리고, 끝에 붙은 줄바꿈은 프로토콜이 지운다 — 모델은 문단을 나눠 답하므로 그 손실이
 * 실제로 일어난다. JSON 으로 감싸면 줄바꿈이 `\n`으로 이스케이프되어 한 줄에 실린다.
 *
 * ⚠️ **`ApiResponse` 봉투를 씌우지 않는다** — 전역 규약(`global/apipayload`)의 예외가 여기서
 * 생긴다. 봉투는 «요청 하나에 응답 하나»를 전제로 `success`·`code`·`message`를 매기는데, SSE 는
 * 한 응답 안에서 이벤트가 여러 번 나가므로 그 값이 조각마다 되풀이될 뿐 아무것도 말하지 않는다.
 * 대신 **오류 이벤트만은 봉투와 같은 이름**(`code`·`message`)을 쓴다
 * (`AssistantStreamErrorResponse`) — 화면의 오류 처리가 두 벌이 되지 않게 하기 위해서다.
 */
public record AssistantAnswerDeltaResponse(
        @Schema(description = "답변 본문의 조각. 앞뒤 조각과 이어 붙이면 done 이벤트의 answer 와 글자 하나까지 같다")
                String text) {}
