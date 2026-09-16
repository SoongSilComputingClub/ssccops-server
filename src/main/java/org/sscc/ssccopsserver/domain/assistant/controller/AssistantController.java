package org.sscc.ssccopsserver.domain.assistant.controller;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.validation.Valid;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.sscc.ssccopsserver.domain.assistant.dto.AssistantAnswerDeltaResponse;
import org.sscc.ssccopsserver.domain.assistant.dto.AssistantQueryRequest;
import org.sscc.ssccopsserver.domain.assistant.dto.AssistantQueryResponse;
import org.sscc.ssccopsserver.domain.assistant.dto.AssistantStreamErrorResponse;
import org.sscc.ssccopsserver.domain.assistant.dto.AssistantSuggestionsResponse;
import org.sscc.ssccopsserver.domain.assistant.service.AssistantAnswerSink;
import org.sscc.ssccopsserver.domain.assistant.service.AssistantService;
import org.sscc.ssccopsserver.domain.assistant.service.AssistantStreamClosedException;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.apipayload.code.error.ErrorCode;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMember;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;

import lombok.extern.slf4j.Slf4j;

/*
 * 규정 도우미 질의 API (#403 · #406 · #447 · 기획안 §10 · §11).
 *
 * ══ 코퍼스 컨트롤러와 나뉜 이유 ═════════════════════════════════
 *
 * **인가 요구가 다르다.** 여기는 **인증만**이고(규정은 회원에게 공개된 문서다), 코퍼스를 바꾸는
 * `RagDocumentController`는 클래스 레벨 `@RequireAuthority(RAG_DOCUMENT_MANAGE)`다 — 코퍼스
 * 변경은 **모든 답변의 근거를 갈아치우는 조작**이라 프롬프트 인젝션 완화의 첫째 층이기도 하다
 * (§6.4). 한 클래스에 두면 클래스 레벨로 걸 수 없어 핸들러마다 붙이게 되고, **하나 빠뜨리는
 * 순간 코퍼스가 열린다.**
 *
 * ══ 질의가 둘인 이유 — 그리고 둘 다 남긴 이유 (#447) ════════════
 *
 * | | |
 * |---|---|
 * | `POST /v1/assistant/queries` | 다 만들고 한 번에. `ApiResponse` 봉투 그대로 |
 * | `POST /v1/assistant/queries/stream` | **SSE.** 화면이 쓰는 것은 이쪽이다 |
 *
 * 기존 경로를 SSE 로 **갈아치우지 않았다.** ① 응답 유형을 바꾸는 것은 OpenAPI 하위 호환
 * 게이트가 막는 변경이고(#412), ② 도구·스크립트가 «질문 하나에 JSON 하나»로 부를 자리가
 * 남아야 하며(MCP 는 아직 규정 도우미를 부르지 않지만 그 자리가 여기다 · #385), ③ 골든셋이
 * 지표를 그 길로 잰다(#405 — SSE 로 재려면 시험지가 전송을 알아야 한다).
 *
 * **대신 컨트롤러만 둘이고 그 아래는 한 벌이다** — 프롬프트·검색·인용 해석이 갈리면 «스트리밍
 * 에서만 다른 답»이 생기는데 지표는 한쪽만 본다(`AssistantServiceImpl`).
 *
 * ⚠️ **SSE 에는 `ApiResponse` 봉투를 씌우지 않는다 — 전역 규약(`global/apipayload`)의 예외다.**
 * 봉투는 «요청 하나에 응답 하나»를 전제로 `success`·`code`·`message`를 매기는데 SSE 는 한 응답
 * 안에서 이벤트가 여러 번 나가므로, 씌우면 그 셋이 조각마다 되풀이될 뿐 아무것도 말하지 않는다.
 * 대신 **오류 이벤트만은 봉투와 같은 이름**(`code`·`message`)을 쓴다 — 화면의 오류 처리를 두 벌로
 * 만들지 않기 위해서다.
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
@Slf4j
@RestController
@RequestMapping("/v1/assistant")
public class AssistantController {

    /** SSE `delta` — 본문 조각. 이벤트 이름을 화면과 나눠 갖는 상수는 여기 셋이 전부다 */
    private static final String DELTA_EVENT = "delta";

    /** SSE `done` — 답·인용·판본이 확정됐다. 거절도 이것 하나로 끝난다 */
    private static final String DONE_EVENT = "done";

    /** SSE `error` — 첫 바이트 뒤의 실패. 그 앞의 실패는 상태 코드로 나간다 */
    private static final String ERROR_EVENT = "error";

    /*
     * 모델 상한을 넘기는 여유 — **`SseEmitter`의 상한을 따로 선언하지 않는 이유다.**
     *
     * 같은 사실을 두 벌 두면 «모델은 20초를 기다리는데 응답은 10초에 닫히는» 조합이 만들어지고,
     * 그 고장은 «긴 답변에서만 글자가 나오다 끊기는» 모양으로 나타난다. 그래서 상한은 언제나
     * `ssccops.assistant.gemini.call-timeout`보다 이만큼 넉넉하다 — 잘라야 할 때는 모델 쪽이
     * 먼저 자르고 우리는 그것을 오류 이벤트로 옮긴다.
     */
    private static final Duration STREAM_TIMEOUT_MARGIN = Duration.ofSeconds(10);

    private final AssistantService assistantService;

    private final long streamTimeoutMillis;

    public AssistantController(
            AssistantService assistantService,
            @Value("${ssccops.assistant.gemini.call-timeout}") Duration modelCallTimeout) {

        this.assistantService = assistantService;
        this.streamTimeoutMillis = modelCallTimeout.plus(STREAM_TIMEOUT_MARGIN).toMillis();
    }

    /*
     * 질의 — **거절이 정상 응답이다.** 근거를 찾지 못하면 200에 `answered: false`이며, 오류가
     * 아닌 것은 화면이 그 문구를 말풍선으로 그려야 하기 때문이다.
     */
    @Operation(
            summary = "규정 도우미 질의 (한 번에)",
            description =
                    "질문 하나에 답하고 근거가 된 인용을 함께 내린다. **화면은 보통 /queries/stream 을 쓴다** —"
                            + " 이 경로는 답이 다 만들어질 때까지 기다렸다가 한 번에 주며, 도구·스크립트처럼"
                            + " «질문 하나에 JSON 하나»가 필요한 자리를 위해 남아 있다. 두 경로는 프롬프트·"
                            + " 검색·인용 해석을 공유하므로 같은 질문에 같은 답을 낸다."
                            + " **검색 대상은 색인이 끝났고(INDEXED) 시행 중인(EFFECTIVE) 판본의 청크뿐**이다"
                            + " — 의결 전 개정안도, 색인 중인 문서도 답변의 근거가 되지 않는다. 임계값을 넘는"
                            + " 근거가 없으면 **모델을 부르지 않고** answered=false와 정해진 안내 문구를"
                            + " 돌려주며 citations는 빈 배열이다(null이 아니다). **모델은 조 번호가 아니라"
                            + " 발췌 번호로 출처를 단다** — 본문에 [3]이 박히고 citations[].ref 가 그 3이며,"
                            + " 범위 밖의 번호는 본문에서도 지워진다. 그래서 «없는 조를 인용한다»가 구조적으로"
                            + " 일어나지 않는다. 조·쪽 표기는 서버가 붙인 citations[].marker(제7조 · 부칙"
                            + " 제3조 · p.12 · 문서명)이고, 본문의 번호를 그대로 둘지 이 표기로 갈아 그릴지는"
                            + " 화면이 고른다. 모델이 출처를 하나도 달지 않으면 이 경로는 답을 통째로 버리고"
                            + " 같은 거절을 돌려준다. citations의 citationType이 어느 필드가 채워졌는지를"
                            + " 말한다 — ARTICLE이면 chapter·article·supplementary, PAGE면 page이며"
                            + " 반대쪽은 null이다(서버가 대체값을 만들지 않는다). **clause 는 언제나 null**"
                            + " 이다(번호 참조에는 항 정보가 없다 — 항의 내용은 snippet 이 보여 준다). 한"
                            + " 답변에 두 유형이 섞이는 것이 정상이다. applyStatus·effectiveDate는 답변 위의"
                            + " «시행 기준» 배지가 쓰는 값이고 거절일 때는 둘 다 null이다. 질문이 1,000자를"
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
     * 흘려보내는 질의 (#447) — **화면이 쓰는 경로다.**
     *
     * 답 한 건이 실측 7.5~12.2초인데 그동안 화면이 비어 있었다(#447). 글자를 흘려보내면 같은
     * 생성 시간에 첫 글자가 1~2초에 닿는다.
     *
     * **거절의 계단은 `queryStreaming`이 돌아오기 전에 끝난다** — 그래서 404·413·503·403·429가
     * 종전 그대로 상태 코드와 `ApiResponse` 봉투로 나가고, `SseEmitter`는 그 뒤에야 돌아간다.
     * 이 순서가 뒤집히면 «글자가 나오다가 사실은 한도 초과였다»가 성립한다.
     */
    @Operation(
            summary = "규정 도우미 질의 (SSE 스트리밍)",
            description =
                    "같은 질문에 **답을 흘려보내며** 답한다(text/event-stream). 이벤트는 셋이다 —"
                            + " `delta`(본문 조각 `{text}`. 이어 붙이면 done 의 answer 와 글자 하나까지"
                            + " 같다) · `done`(한 번에 받는 경로와 **같은 응답 본문** — answer·citations·"
                            + " applyStatus·effectiveDate·answered·conversationId) · `error`(첫 바이트"
                            + " **뒤**의 실패. `{code, message}`이며 이름이 ApiResponse 의 오류와 같다)."
                            + " **이 경로의 이벤트에는 ApiResponse 봉투가 없다** — 한 응답에 이벤트가 여러"
                            + " 번 나가므로 success/code/message 를 조각마다 되풀이할 자리가 없다."
                            + " **첫 바이트 전의 거절은 종전 그대로 상태 코드다**: 404"
                            + " ASSISTANT_DISABLED · 413 ASSISTANT_QUESTION_TOO_LONG · 503"
                            + " ASSISTANT_UNAVAILABLE · 403 ASSISTANT_CONVERSATION_FORBIDDEN · 429"
                            + " ASSISTANT_RATE_LIMITED · 400(빈 질문). 근거를 찾지 못한 거절도 여기"
                            + " 속한다 — delta 가 한 번도 오지 않고 done 하나가 answered=false 와 정해진"
                            + " 안내 문구를 싣는다. 흘려보내기 시작한 뒤의 모델 실패는 상태 코드를 바꿀 수"
                            + " 없으므로 error 이벤트(ASSISTANT_UPSTREAM_FAILED)이며, **그때까지 그려진"
                            + " 글자는 화면에 남는다.** ⚠️ 모델이 출처를 하나도 달지 않은 답은 이 경로에서만"
                            + " 회수되지 않는다 — done 이 answered=false 에 **흘려보낸 문장 그대로**를 싣고"
                            + " (정해진 안내 문구가 아니다) 화면은 그 말풍선에 «근거 없음»을 표시한다. 이미"
                            + " 읽힌 문장을 다른 문장으로 갈아치우는 것이 기각된 «사후 철회»라서다. 그 밖의"
                            + " 계약(번호 참조 · 인용 모양 · 대화 식별자 · 한도)은 /queries 와 같다.")
    /*
     * 200의 스키마를 **`done` 이벤트의 본문**으로 못 박는다. 비워 두면 springdoc 이 반환 타입
     * (`SseEmitter`)을 그대로 스키마로 만들어 «이벤트 흐름»이 아니라 서블릿 타입을 문서에 싣는다 —
     * 웹이 읽을 값은 그쪽이 아니라 이 record다.
     */
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description =
                    "이벤트 흐름. delta 는 {text}, done 은 아래 본문, error 는 {code, message}이며"
                            + " 셋 다 ApiResponse 봉투가 없다.",
            content =
                    @Content(
                            mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                            schema = @Schema(implementation = AssistantQueryResponse.class)))
    @PostMapping(value = "/queries/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter queryStream(
            @Valid @RequestBody AssistantQueryRequest request, @CurrentMember MemberEntity member) {

        SseEmitter emitter = new SseEmitter(streamTimeoutMillis);
        SseAnswerSink sink = new SseAnswerSink(emitter);
        emitter.onTimeout(sink::close);
        emitter.onError(failure -> sink.close());

        assistantService.queryStreaming(request, member, sink);
        return emitter;
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

    /*
     * 도메인이 흘려보낸 것을 SSE 로 옮기는 자리 (#447).
     *
     * **이것이 컨트롤러에 있는 이유**는 `SseEmitter`가 서블릿 응답에 매인 타입이기 때문이다 —
     * 서비스가 그것을 직접 들면 «규정 도우미를 부르려면 웹 계층이 있어야 한다»가 되어 골든셋과
     * 단위 테스트가 컨텍스트를 함께 세워야 한다.
     *
     * ⚠️ **`delta`가 실패하면 예외를 던져 구독을 끊는다.** 사용자가 탭을 닫았는데도 생성을 끝까지
     * 돌리면 아무도 읽지 않는 답에 무료 쿼터를 쓴다(§11). `done`·`failed`는 던지지 않는다 — 그
     * 시점에는 끊을 구독이 없고, 이미 닫힌 응답에 쓰려다 나는 예외가 서블릿 컨테이너로 올라가
     * «이미 끝난 요청»의 스택 트레이스를 남길 뿐이다.
     */
    private static final class SseAnswerSink implements AssistantAnswerSink {

        private final SseEmitter emitter;

        private final AtomicBoolean closed = new AtomicBoolean();

        private SseAnswerSink(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void delta(String text) {
            if (!send(DELTA_EVENT, new AssistantAnswerDeltaResponse(text))) {
                throw new AssistantStreamClosedException();
            }
        }

        @Override
        public void done(AssistantQueryResponse response) {
            send(DONE_EVENT, response);
            close();
        }

        @Override
        public void failed(ErrorCode errorCode) {
            send(
                    ERROR_EVENT,
                    new AssistantStreamErrorResponse(errorCode.getCode(), errorCode.getMessage()));
            close();
        }

        /** 보냈나 — 이미 닫혔거나 쓰다 실패했으면 {@code false}이고 그 뒤로는 아무것도 보내지 않는다 */
        private boolean send(String event, Object payload) {
            if (closed.get()) {
                return false;
            }
            try {
                emitter.send(
                        SseEmitter.event().name(event).data(payload, MediaType.APPLICATION_JSON));
                return true;

            } catch (IOException | IllegalStateException failure) {
                // 받는 쪽이 사라졌다 — 흔한 일이라 WARN 이 아니다(탭을 닫으면 여기로 온다)
                log.debug("규정 도우미 SSE 전송이 실패했다 — event={}", event, failure);
                close();
                return false;
            }
        }

        private void close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    emitter.complete();
                } catch (RuntimeException alreadyGone) {
                    log.debug("규정 도우미 SSE 를 닫는 중", alreadyGone);
                }
            }
        }
    }
}
