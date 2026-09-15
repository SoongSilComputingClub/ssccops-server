package org.sscc.ssccopsserver.domain.assistant.controller;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.assistant.dto.AssistantQueryRequest;
import org.sscc.ssccopsserver.domain.assistant.dto.AssistantQueryResponse;
import org.sscc.ssccopsserver.domain.assistant.dto.AssistantSuggestionsResponse;
import org.sscc.ssccopsserver.domain.assistant.service.AssistantService;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMember;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 규정 도우미 질의 API (#403 · #406 · 기획안 §10 · §11).
 *
 * ══ 코퍼스 컨트롤러와 나뉜 이유 ═════════════════════════════════
 *
 * **인가 요구가 다르다.** 여기는 **인증만**이고(규정은 회원에게 공개된 문서다), 코퍼스를 바꾸는
 * `RagDocumentController`는 클래스 레벨 `@RequireAuthority(RAG_DOCUMENT_MANAGE)`다 — 코퍼스
 * 변경은 **모든 답변의 근거를 갈아치우는 조작**이라 프롬프트 인젝션 완화의 첫째 층이기도 하다
 * (§6.4). 한 클래스에 두면 클래스 레벨로 걸 수 없어 핸들러마다 붙이게 되고, **하나 빠뜨리는
 * 순간 코퍼스가 열린다.**
 *
 * ══ 경로에 회원 식별자가 없다 ═══════════════════════════════════
 *
 * 대상은 언제나 인증 주체 본인이다 — 폼 초안(`/responses/draft`)이 세운 규칙이며, 경로에
 * `mbrId`를 두면 «남의 것을 물을 수 있는가»를 핸들러마다 다시 판정해야 한다.
 *
 * ══ `/public/v1/**` 아래에 두지 않는다 ══════════════════════════
 *
 * 그 접두사에 핸들러를 더하는 것은 permitAll을 더하는 것과 같다(루트 AGENTS.md). 도우미는
 * 익명에게 열리지 않는다 — 답변 재료가 공개된 회칙이라 해도 코퍼스에 무엇이 올라올지는
 * 운영 규칙이 지키는 값이고(§11), 그 경계를 익명 경로가 넘어서면 안 된다.
 *
 * ══ 대화 초기화가 여기 있는 이유 ═══════════════════════════════
 *
 * `DELETE /v1/assistant/conversations/{id}`는 **되돌릴 상태가 생겨서** 들어왔다(#406 · Phase 2).
 * Phase 1에서 이 핸들러도 화면의 `↺`도 없었던 것은 «초기화»가 아무 일도 하지 않는데 사용자는
 * 초기화됐다고 믿게 되기 때문이다(§13.1). 경로에 회원 식별자가 없는 것은 질의와 같고, 대신
 * **대화 식별자의 앞부분이 그 자리를 대신한다** — 서버가 `{회원 식별자}:{탭 UUID}`로 발급하고
 * 서버가 검증한다(§7.4).
 *
 * ══ 한도를 여기서 보지 않는다 ═══════════════════════════════════
 *
 * 질의 한도(#404)는 필터도 인터셉터도 아니고 **서비스가 모델을 부르기 직전에** 본다
 * (`AssistantRateLimiter`). 경로로 거는 층을 만들면 «어떤 요청이 쿼터를 쓰는가»가 컨트롤러
 * 목록으로 흩어지는데, 실제로 쿼터를 쓰는 것은 경로가 아니라 **Gemini를 부르는 코드 한 줄**이다
 * — 추천 질문이 같은 컨트롤러에 있으면서도 세어지지 않는 이유가 그것이다(DB만 읽는다).
 */
@RestController
@RequestMapping("/v1/assistant")
@RequiredArgsConstructor
public class AssistantController {

    private final AssistantService assistantService;

    /*
     * 질의 — **거절이 정상 응답이다.** 근거를 찾지 못하면 200에 `answered: false`이며, 오류가
     * 아닌 것은 화면이 그 문구를 말풍선으로 그려야 하기 때문이다.
     */
    @Operation(
            summary = "규정 도우미 질의",
            description =
                    "질문 하나에 답하고 근거가 된 인용을 함께 내린다. **검색 대상은 색인이 끝났고(INDEXED) 시행"
                            + " 중인(EFFECTIVE) 판본의 청크뿐**이다 — 의결 전 개정안도, 색인 중인"
                            + " 문서도 답변의 근거가 되지 않는다. 임계값을 넘는 근거가 없으면"
                            + " **모델을 부르지 않고** answered=false와 정해진 안내 문구를 돌려주며"
                            + " citations는 빈 배열이다(null이 아니다). 모델이 답했더라도 그 인용이"
                            + " 실제 발췌와 대조되지 않으면 그 인용은 버려지고, 남은 인용이 하나도"
                            + " 없으면 답변 자체를 버리고 같은 거절을 돌려준다. citations의"
                            + " citationType이 어느 필드가 채워졌는지를 말한다 — ARTICLE이면"
                            + " chapter·article·clause·supplementary, PAGE면 page이며 반대쪽은"
                            + " null이다(서버가 대체값을 만들지 않는다). 한 답변에 두 유형이 섞이는"
                            + " 것이 정상이다. applyStatus·effectiveDate는 답변 위의 «시행 기준»"
                            + " 배지가 쓰는 값이고 거절일 때는 둘 다 null이다. 질문이 1,000자를"
                            + " 넘으면 413 ASSISTANT_QUESTION_TOO_LONG, 모델 호출이 실패하면 503"
                            + " ASSISTANT_UPSTREAM_FAILED, 키가 없어 배선이 서지 않았으면 503"
                            + " ASSISTANT_UNAVAILABLE, 기능이 꺼져 있으면 404 ASSISTANT_DISABLED다."
                            + " 한도를 넘으면 429 ASSISTANT_RATE_LIMITED이며(회원당 1분 5회 · 하루"
                            + " 50회, 그리고 전원이 나눠 쓰는 분당 한도) message가 어느 한도인지에"
                            + " 따라 «잠시 뒤»와 «내일»을 가른다 — 무료 쿼터가 API 키 단위의 공유"
                            + " 자원이라 서버가 공급자보다 먼저 끊는다. 질문과 답변은 어디에도"
                            + " 저장되지 않는다. conversationId는 **서버가 발급한다** — 비워"
                            + " 보내면 새 대화가 열리고 그 값이 응답에 실려 오며, 다음 질문에"
                            + " 그대로 실으면 앞선 턴들이 맥락으로 들어간다(거절일 때도 실려"
                            + " 온다). 남의 것이거나 서버가 발급하지 않은 모양이면 403"
                            + " ASSISTANT_CONVERSATION_FORBIDDEN이니 들고 있던 값을 버리고 새"
                            + " 대화로 다시 보내면 된다. 24시간 동안 쓰이지 않은 대화는 만료되어"
                            + " **빈 이력으로 이어진다** — 오류가 아니라 새 대화처럼 보이는 것이"
                            + " 정상이다. 검색은 언제나 이번 질문 하나로 한다.")
    @PostMapping("/queries")
    public ApiResponse<AssistantQueryResponse> query(
            @Valid @RequestBody AssistantQueryRequest request, @CurrentMember MemberEntity member) {

        return ApiResponse.success(assistantService.query(request, member));
    }

    /*
     * 추천 질문 — **서버가 내린다**(§13.3). 코퍼스가 화면에서 바뀌므로 웹에 하드코딩하면 업로드
     * 다음 날부터 거짓말을 한다.
     *
     * `@CurrentMember`를 받는 것은 **질의와 같은 계단을 쓰기 위해서다** — 미가입자에게 추천
     * 질문만 보이고 누르면 403이 되는 상태를 만들지 않는다(값 자체는 쓰지 않는다).
     */
    @Operation(
            summary = "규정 도우미 추천 질문",
            description =
                    "지금 코퍼스가 답할 수 있는 질문을 최대 3개 내린다. 후보는 «어느 문서가 있어야 답할 수"
                            + " 있는가»로 묶여 있어, 그 문서가 시행 중이 아니면 그 질문은 내려가지"
                            + " 않는다. **코퍼스가 비어 있으면 빈 배열이며 그것이 새 환경의 정상"
                            + " 상태다** — 화면은 그때 고지 문구만 그린다.")
    @GetMapping("/suggestions")
    public ApiResponse<AssistantSuggestionsResponse> suggestions(
            @CurrentMember MemberEntity member) {

        return ApiResponse.success(assistantService.suggestions());
    }

    /*
     * 대화 초기화 — **없는 대화를 지우는 것도 성공이다.** 24시간 슬라이딩 만료가 지난 대화와
     * 아직 한 번도 묻지 않은 식별자를 가를 값이 서버에 없고, 화면이 할 일은 «처음 화면으로
     * 되돌린다»로 같다. 대신 **남의 것이면 403**이다 — 지우는 것도 남의 대화에 닿는 일이다.
     */
    @Operation(
            summary = "규정 도우미 대화 초기화",
            description =
                    "그 대화의 이력을 지운다 — 패널의 ↺ 버튼이 부르는 자리다. **없는 대화를 지우는 것도"
                            + " 200이다**: 24시간 동안 쓰이지 않아 만료된 대화와 아직 한 번도 묻지"
                            + " 않은 식별자를 서버가 가를 수 없고, 화면이 할 일이 «처음 화면으로"
                            + " 되돌린다»로 같기 때문이다. conversationId는 서버가 발급한"
                            + " {회원 식별자}:{탭 UUID} 모양이며, 남의 것이거나 그 모양이 아니면"
                            + " 403 ASSISTANT_CONVERSATION_FORBIDDEN이다 — 지우는 것도 남의 대화에"
                            + " 닿는 일이라 질의와 같은 규칙을 쓴다. 기능이 꺼져 있으면 404"
                            + " ASSISTANT_DISABLED다.")
    @DeleteMapping("/conversations/{conversationId}")
    public ApiResponse<Void> clearConversation(
            @PathVariable String conversationId, @CurrentMember MemberEntity member) {

        assistantService.clearConversation(conversationId, member);
        return ApiResponse.successWithNoData();
    }
}
