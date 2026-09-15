package org.sscc.ssccopsserver.domain.assistant.dto;

import jakarta.validation.constraints.NotBlank;

import io.swagger.v3.oas.annotations.media.Schema;

/*
 * 질의 요청 — 질문 한 줄이 전부다 (#403 · 기획안 §10).
 *
 * **대상이 실리지 않는다.** 회원 식별자도(대상은 언제나 인증 주체 본인 — 폼 초안
 * `/responses/draft`가 세운 규칙), 문서 식별자도(무엇을 볼지는 판본의 상태가 정한다 —
 * `INDEXED && EFFECTIVE`), 임계값·topK 같은 검색 손잡이도 없다. **요청이 고를 수 있는 것이
 * 없다는 것이 이 API의 성질**이며, 그래야 «어떤 근거로 답했는가»가 서버의 상태 하나로 설명된다.
 *
 * **`conversationId`도 없다** — 대화 메모리는 Phase 2(#406)다. 미리 받아 두고 무시하면 화면이
 * «이어지는 대화»를 그린 채 매번 처음부터 답하게 된다.
 *
 * 길이 상한(1,000자)을 `@Size`로 걸지 않은 것은 그 거절이 **413
 * `ASSISTANT_QUESTION_TOO_LONG`**이어야 하기 때문이다(기획안 §10) — `@Valid`는 400을 낸다.
 * 빈 질문은 요청이 형식을 어긴 것이라 그대로 400이다.
 */
public record AssistantQueryRequest(
        @Schema(
                        description = "규정에 대한 질문. 1,000자를 넘으면 413 ASSISTANT_QUESTION_TOO_LONG",
                        example = "정회원 승격 조건은 무엇인가요?")
                @NotBlank(message = "질문(question)을 입력해 주세요.")
                String question) {}
