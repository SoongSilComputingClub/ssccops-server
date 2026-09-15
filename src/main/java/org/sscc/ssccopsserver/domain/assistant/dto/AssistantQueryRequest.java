package org.sscc.ssccopsserver.domain.assistant.dto;

import jakarta.validation.constraints.NotBlank;

import io.swagger.v3.oas.annotations.media.Schema;

/*
 * 질의 요청 — 질문과, 이어 가는 대화의 식별자 (#403 · #406 · 기획안 §7.4 · §10).
 *
 * **대상이 실리지 않는다.** 회원 식별자도(대상은 언제나 인증 주체 본인 — 폼 초안
 * `/responses/draft`가 세운 규칙), 문서 식별자도(무엇을 볼지는 판본의 상태가 정한다 —
 * `INDEXED && EFFECTIVE`), 임계값·topK 같은 검색 손잡이도 없다. **요청이 고를 수 있는 것이
 * 없다는 것이 이 API의 성질**이며, 그래야 «어떤 근거로 답했는가»가 서버의 상태 하나로 설명된다.
 *
 * `conversationId`도 **요청이 정하는 값이 아니다** — 서버가 발급한 것을 화면이 그대로 돌려보낼
 * 뿐이고(§7.4), 앞부분이 그 사람의 식별자인지를 서버가 본다. 클라이언트가 만들게 두면 남의
 * 값을 넣어 **남의 대화를 읽을 수 있다.**
 *
 * 길이 상한(1,000자)을 `@Size`로 걸지 않은 것은 그 거절이 **413
 * `ASSISTANT_QUESTION_TOO_LONG`**이어야 하기 때문이다(기획안 §10) — `@Valid`는 400을 낸다.
 * 빈 질문은 요청이 형식을 어긴 것이라 그대로 400이다. 같은 줄기로 `conversationId`에도
 * `@Pattern`을 걸지 않는다 — 모양이 틀린 값과 남의 값이 **같은 거절**(403
 * `ASSISTANT_CONVERSATION_FORBIDDEN`)이어야 하고, 판정은 발급한 자리와 같은 곳에 있어야 한다
 * (`AssistantConversations`).
 */
public record AssistantQueryRequest(
        @Schema(
                        description = "규정에 대한 질문. 1,000자를 넘으면 413 ASSISTANT_QUESTION_TOO_LONG",
                        example = "정회원 승격 조건은 무엇인가요?")
                @NotBlank(message = "질문(question)을 입력해 주세요.")
                String question,
        @Schema(
                        description =
                                "이어 갈 대화. **직전 응답의 conversationId를 그대로 싣는다** — 서버가 발급한"
                                        + " 값이며 클라이언트가 만들지 않는다. 비우면(또는 첫 질문이면) 새"
                                        + " 대화가 열리고 발급된 값이 응답에 실려 온다. 남의 것이거나 서버가"
                                        + " 발급하지 않은 모양이면 403"
                                        + " ASSISTANT_CONVERSATION_FORBIDDEN이며, 그때 화면은 들고 있던"
                                        + " 값을 버리고 새 대화로 다시 보낸다.",
                        example = "12:9f1c2f5e-3a2b-4a6a-9f0e-7c3d5b1a2e44")
                String conversationId) {}
