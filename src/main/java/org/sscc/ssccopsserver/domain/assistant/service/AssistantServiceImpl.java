package org.sscc.ssccopsserver.domain.assistant.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.sscc.ssccopsserver.domain.assistant.code.AssistantCorpusState;
import org.sscc.ssccopsserver.domain.assistant.code.error.AssistantErrorCode;
import org.sscc.ssccopsserver.domain.assistant.dto.AssistantQueryRequest;
import org.sscc.ssccopsserver.domain.assistant.dto.AssistantQueryResponse;
import org.sscc.ssccopsserver.domain.assistant.dto.AssistantSuggestionsResponse;
import org.sscc.ssccopsserver.domain.assistant.entity.RagDocumentEntity;
import org.sscc.ssccopsserver.domain.assistant.repository.RagDocumentRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import reactor.core.Exceptions;
import reactor.core.scheduler.Schedulers;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/*
 * 질의 한 건 — 검색 → (근거가 있으면) 생성 → 인용 해석 (#403 · #447 · 기획안 §6).
 *
 * ══ 방어선이 셋이고 순서가 중요하다 ═════════════════════════════
 *
 *   1차  **임계값을 넘는 청크가 없으면 모델을 부르지 않는다.** 프롬프트의 «모르면 모른다고
 *        하라»는 지시를 모델은 종종 어기는데, **아예 부르지 않으면 어길 수 없다.**
 *   2차  프롬프트가 다섯 규칙을 건다(`AssistantPrompt`).
 *   3차  **모델이 쓴 출처가 우리가 넣어 준 발췌의 번호인지 본다**(`CitationVerifier`).
 *        범위 밖이면 그 토큰은 본문에서도 사라진다.
 *
 * **거절이 이 기능의 가장 중요한 동작이다.** 운영진이 답변을 근거로 사람의 자격을 판단한다.
 *
 * ══ 답이 두 모양으로 나간다 — 아래가 같고 위만 다르다 (#447) ════
 *
 * | | 무엇이 | 실패가 어디로 |
 * |---|---|---|
 * | `query` | 다 만들고 한 번에 | 전부 상태 코드 |
 * | `queryStreaming` | 조각을 흘려보내고 마지막에 인용·판본 | **첫 바이트 전**은 상태 코드, 그 뒤는 오류 이벤트 |
 *
 * **검색·프롬프트·인용 해석은 한 벌이다**(`prepare` · `AssistantPrompt` · `CitationVerifier`) —
 * 갈리면 «스트리밍에서만 틀린 답»이 생기고 골든셋은 한쪽만 본다.
 *
 * ⚠️ **거절의 계단은 스트림이 열리기 전에 끝난다.** 404·413·503·403·429와 «근거 없음»은 모두
 * `prepare` 안에서 판정되며, 그래서 SSE 로 바꾸고도 그 응답들이 종전 그대로 상태 코드로 나간다.
 * 순서를 흐트러뜨리면 «화면에 글자가 나오다가 사실은 한도 초과였다»가 성립한다.
 *
 * ⚠️ **흘려보낸 뒤에는 답을 버리지 않는다.** 인용이 하나도 확인되지 않은 답을 통째로 버리는 것은
 * `query`의 동작이고, 스트리밍에서는 그 글자가 이미 읽혔으므로 회수하지 않고 «근거 없음»으로
 * 표시한다(`AssistantQueryResponse.ungrounded`). 번호 참조 뒤 그 경우는 «출처를 하나도 달지
 * 않은 답»으로 좁아졌다 — 옛 계약에서 흔했던 «없는 조를 지어낸다»는 범위 검사가 애초에 막는다.
 *
 * ══ 그 앞에 레이트 리밋이 있다 ══════════════════════════════════
 *
 * 위 셋이 «무엇을 답할 것인가»의 방어선이라면 `AssistantRateLimiter`는 «얼마나 답할 것인가»의
 * 방어선이다(#404 · §11). 무료 쿼터가 API 키 단위의 공유 자원이라 한 사람의 루프가 전원의
 * 답변을 멈춘다 — 그래서 **모델을 부르기 전에** 429로 끊는다. 순서의 이유는 `prepare` 안에 있다.
 *
 * ══ 검색 조건은 둘이고 «조회 뒤 if»가 아니다 ════════════════════
 *
 * 볼 수 있는 것은 `INDEXED && EFFECTIVE`인 판본의 청크뿐이다(§5.5). 그 판정은 JPA 질의
 * (`findSearchable`) 한 곳에 박혀 있고, 거기서 나온 식별자 집합이 **그대로 벡터 검색의
 * 필터**(`ragDocId in [...]`)가 된다 — 받아 온 뒤 거르는 코드를 두지 않는 것은 `PublicEventServiceImpl`
 * 과 같은 태도다. 청크 메타의 `applyStatus`로 거는 안은 틀리다: 그 값은 색인 시점의 것이라
 * 전환(#401) 뒤 재색인 전까지 낡아 있다(리포지토리 주석).
 *
 * **그래도 돌아온 청크를 한 번 더 본다** — 저장소가 필터를 무시했거나(스텁이 그렇다) 고아 청크가
 * 섞였을 때 판본을 모르는 근거로 답하지 않기 위해서다. 그것은 필터의 대체가 아니라 «판본을
 * 붙일 수 없는 청크는 인용할 수 없다»는 사실의 표현이다.
 *
 * ══ 트랜잭션이 모델 호출을 감싸지 않는다 ════════════════════════
 *
 * 판본 목록을 읽는 것만 트랜잭션이고(`searchableDocuments`), 임베딩·생성은 그 밖에서 돈다 —
 * 색인 워커가 같은 이유로 트랜잭션을 셋으로 쪼갠 자리이며(#400), Supabase Free의 커넥션을 Gemini
 * 왕복 동안 쥐고 있을 수 없다(ssccops#324). 그래서 엔티티를 들고 나가지 않고
 * `SearchableDocument`로 옮겨 담는다. **스트리밍에서는 더 그렇다** — 그 구간이 요청 스레드조차
 * 아니다.
 *
 * ══ 대화는 생성의 맥락이고 검색의 재료가 아니다 (#406) ═════════
 *
 * 앞선 턴들이 프롬프트의 가운데에 들어가고(시스템 → 이력 → 이번 발췌·질문) **검색어는 언제나
 * 이번 질문 하나**다. 담기는 것도 질문과 답변 둘뿐이라 발췌는 매 턴 새로 만든다 — 그 이유와
 * 기각한 길은 `AssistantConversations`에 있다.
 *
 * **한 턴은 답했을 때만 남는다.** 거절과 오류는 이력에 닿지 않으므로, 이어 묻는 사람이 보는
 * 맥락에는 「찾지 못했습니다」가 섞이지 않는다. **근거 없이 흘러나간 답도 담지 않는다** — 우리가
 * 뒤에 서지 않는 문장을 다음 턴의 본보기로 두지 않는다.
 *
 * ══ 남기지 않는 것 ═════════════════════════════════════════════
 *
 * **질문도 답변도 어디에도 저장하지 않는다**(§9 · §11 — 질의 로그 표를 두지 않았다). 로그에도
 * 싣지 않는다: 질문에는 사람 이름이 섞여 들어올 수 있고, 로그는 Kibana에 남는다(ADR-0024).
 * 남기는 것은 «누가·몇 개의 근거로·답했는가·**어느 구간에서** 얼마나 걸렸는가»다(#453 · `QueryTimeline`).
 *
 * ⚠️ **대화 메모리는 그 규칙의 예외가 아니라 경계다.** 질문과 답변이 24시간 슬라이딩 만료의
 * 힙 캐시에 머무는 것은 «이어 말하기»가 그것 없이는 성립하지 않기 때문이고, 그 값은 디스크에도
 * 로그에도 닿지 않으며 회원 경계를 넘지 않는다(§7.4). 기록으로 남기는 것과 갈리는 지점이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantServiceImpl implements AssistantService {

    private final AssistantFeature assistantFeature;
    private final AssistantQueryPolicy policy;
    private final AssistantRateLimiter rateLimiter;
    private final AssistantSuggestions assistantSuggestions;
    private final CitationVerifier citationVerifier;
    private final AssistantConversations conversations;
    private final RagDocumentRepository ragDocumentRepository;

    /*
     * **둘 다 빈이 없을 수 있다.** Gemini 키가 없으면 채팅 클라이언트도 청크 저장소도 서지
     * 않는다(`AssistantConfig`). 그 상태는 «설정이 덜 된 서버»라 요청의 잘못이 아니고, 부르는
     * 쪽에 알리는 코드가 503 `ASSISTANT_UNAVAILABLE`이다 — 색인 워커가 저장소 없음을 다루는
     * 것과 같은 자리인데, 그쪽은 돌려줄 응답이 없어 대기열을 그대로 둔다(#400).
     */
    private final ObjectProvider<RagChunkStore> ragChunkStore;

    private final ObjectProvider<ChatClient> assistantChatClient;

    @Override
    public AssistantQueryResponse query(AssistantQueryRequest request, MemberEntity member) {
        Prepared prepared = prepare(request, member);
        if (prepared.refusal() != null) {
            return prepared.refusal();
        }

        CitationVerifier.Session session = citationVerifier.open(prepared.chunks());
        session.accept(generate(prepared));
        session.finish();
        return conclude(prepared, session, false);
    }

    /*
     * 흘려보내는 답 (#447).
     *
     * **`prepare`가 돌아올 때까지는 아무것도 나가지 않았다** — 그래서 그 안의 거절은 예외로 던져
     * `GlobalExceptionHandler`가 상태 코드로 받는다(계단이 `query`와 글자 하나까지 같다).
     * 그 뒤부터 응답은 열려 있으므로 실패도 오류 이벤트다.
     *
     * ⚠️ **구독을 우리 손으로 다른 스레드에 올린다**(`subscribeOn`). Spring AI 1.1.8의
     * `GoogleGenAiChatModel.internalStream`이 이미 `boundedElastic`에 올리고 있지만 그것은
     * **라이브러리 내부 결정이라 버전이 오르며 바뀔 수 있고**, 그 줄이 사라지면 여기서 요청
     * 스레드가 생성이 끝날 때까지 붙들린다 — 그러면 `SseEmitter`가 조각을 모았다가 한 번에
     * 내보내므로 **화면에서는 스트리밍이 아니었던 것처럼 보이고** 아무도 원인을 찾지 못한다
     * (MCP 가 `immediateExecution`을 못 박아 둔 것과 같은 자리 · `global/mcp`).
     */
    @Override
    public void queryStreaming(
            AssistantQueryRequest request, MemberEntity member, AssistantAnswerSink sink) {

        Prepared prepared = prepare(request, member);
        if (prepared.refusal() != null) {
            sink.done(prepared.refusal());
            return;
        }

        CitationVerifier.Session session = citationVerifier.open(prepared.chunks());
        prompt(prepared).stream()
                .content()
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        delta -> {
                            prepared.timeline().received();
                            emit(sink, prepared.timeline(), session.accept(delta));
                        },
                        failure -> failed(prepared, sink, failure),
                        () -> {
                            emit(sink, prepared.timeline(), session.finish());
                            sink.done(conclude(prepared, session, true));
                        });
    }

    /*
     * 대화 초기화 — 패널의 `↺`(§13.1). **기능 플래그가 꺼져 있으면 404다**(질의와 같은 계단).
     *
     * 모델도 저장소도 필요 없으므로 배선 없음(503)을 보지 않는다 — 지울 것은 우리 힙에 있다.
     */
    @Override
    public void clearConversation(String conversationId, MemberEntity member) {
        assistantFeature.requireEnabled();
        conversations.clear(member.getId(), conversationId);
        log.info("규정 도우미 대화 초기화 — mbrId={}", member.getId());
    }

    @Override
    public AssistantSuggestionsResponse suggestions() {
        assistantFeature.requireEnabled();

        /*
         * **판정이 한 번이고 그 결과가 두 자리로 나간다**(#449). 추천 질문이 보는 것은
         * «`READY`인가» 하나지만(그 뒤에 물어도 되는지가 그것이다 · §13.3) 같은 값이 응답에도
         * 실려 화면이 빈 상태 문구를 가른다. 여기서 두 번 판정하면 «질문은 비어 있는데 상태는
         * `READY`»가 성립한다.
         */
        AssistantCorpusState corpusState = corpusState();

        return new AssistantSuggestionsResponse(
                corpusState, assistantSuggestions.forCorpus(corpusState));
    }

    /*
     * ── 첫 바이트 전에 끝나는 것 전부 ─────────────────────────────
     *
     * **거절의 순서가 곧 «무엇을 아껴야 하는가»의 순서다** — 뒤로 갈수록 값비싼 자원을
     * 건드린다. 한도(429)를 맨 뒤에 두는 것은 그 앞의 넷이 전부 **쿼터를 한 톨도 쓰지 않는
     * 거절**이기 때문이다: 기능이 꺼져 있거나(404), 질문이 상한을 넘었거나(413), 키가 없어
     * 배선이 서지 않았거나(503), 남의 대화를 넣은(403 · #406) 요청은 애초에 Gemini에 닿지
     * 못하므로 그 사람의 한도를 깎을 이유가 없다. 여기를 지난 요청만이 임베딩을 부른다.
     *
     * **두 경로가 이 메서드를 함께 쓴다**(#447) — 스트리밍이 이 계단을 따로 갖게 되는 순간
     * 한쪽에만 빠진 층이 생기고, 그 고장은 «어떤 경로로 물었는가»에 따라 나타났다 사라진다.
     */
    private Prepared prepare(AssistantQueryRequest request, MemberEntity member) {
        QueryTimeline timeline = new QueryTimeline();
        assistantFeature.requireEnabled();

        String question = requireAskable(request.question());
        RagChunkStore chunkStore = require(ragChunkStore);
        ChatClient chatClient = require(assistantChatClient);
        String conversationId = conversations.open(member.getId(), request.conversationId());
        rateLimiter.requireWithinQuota(member.getId());

        long memberId = member.getId();

        Map<Long, SearchableDocument> searchable = searchableDocuments();
        if (searchable.isEmpty()) {
            /*
             * **새 환경의 기본 상태가 여기다**(§12.5 — 코퍼스는 업로드로만 들어온다). 시행 중인
             * 문서가 하나도 없으면 검색할 것이 없으므로 임베딩조차 부르지 않는다.
             */
            return Prepared.refused(
                    refuse(memberId, "시행 중인 규정 문서가 없다", 0, conversationId, timeline));
        }

        /*
         * **이번 질문 하나로 검색한다** — 이력은 생성에만 들어간다(`AssistantConversations`).
         */
        List<RetrievedChunk> chunks = retrieve(question, searchable, chunkStore);
        timeline.retrieved();
        if (chunks.isEmpty()) {
            return Prepared.refused(
                    refuse(memberId, "임계값을 넘는 청크가 없다", 0, conversationId, timeline));
        }

        return new Prepared(
                memberId,
                question,
                conversationId,
                chatClient,
                chunks,
                conversations.history(conversationId),
                timeline,
                null);
    }

    /*
     * ── 다 흘러나온 뒤 ───────────────────────────────────────────
     *
     * 인용이 하나도 확인되지 않았을 때 **두 경로의 답이 갈리는 유일한 자리**다: 아직 아무것도
     * 나가지 않았으면 답을 통째로 버리고(3차 방어선), 이미 나갔으면 회수하지 않고 «근거 없음»을
     * 싣는다(#447 · `AssistantQueryResponse.ungrounded`). 어느 쪽이든 **대화에는 담지 않는다.**
     */
    private AssistantQueryResponse conclude(
            Prepared prepared, CitationVerifier.Session session, boolean streamed) {

        CitationVerifier.Verified verified = session.verified();
        if (verified.citations().isEmpty()) {
            /*
             * 모델이 답은 했는데 **우리가 넣어 준 발췌를 하나도 가리키지 않았다.** 근거 없는 규정
             * 답변을 우리 이름으로 내보내지 않는다 — 사용자에게는 「찾지 못했다」와 같은 문구이고
             * (화면이 할 일이 같다), 둘을 가르는 값은 이 로그에만 남는다.
             */
            if (streamed && !verified.answer().isEmpty()) {
                log.info(
                        "규정 도우미 근거 없는 답을 흘려보냈다 — mbrId={} 발췌={} 버린인용={}",
                        prepared.memberId(),
                        prepared.chunks().size(),
                        verified.dropped());
                return AssistantQueryResponse.ungrounded(
                        verified.answer(), prepared.conversationId());
            }
            return refuse(
                    prepared.memberId(),
                    "모델의 답에서 검증을 통과한 인용이 없다",
                    prepared.chunks().size(),
                    prepared.conversationId(),
                    prepared.timeline());
        }

        /*
         * **답한 턴만 담는다**(#406). 거절이 이력에 남으면 다음 턴의 맥락에 「찾지 못했습니다」가
         * 섞이고, 그것이 모델에게는 이 대화의 본보기가 된다.
         */
        conversations.remember(prepared.conversationId(), prepared.question(), verified.answer());

        SearchableDocument primary = verified.citations().get(0).source();
        log.info(
                "규정 도우미 답변 — mbrId={} 방식={} 이력={}턴 발췌={} 인용={} 버린인용={} 기준문서={}"
                        + " 검색={}ms 첫수신={}ms 첫송신={}ms 소요={}ms",
                prepared.memberId(),
                streamed ? "스트리밍" : "일괄",
                prepared.history().size() / 2,
                prepared.chunks().size(),
                verified.citations().size(),
                verified.dropped(),
                primary.name(),
                prepared.timeline().retrieval(),
                prepared.timeline().firstChunk(),
                prepared.timeline().firstDelta(),
                elapsedMillis(prepared));

        return new AssistantQueryResponse(
                verified.answer(),
                verified.responses(),
                primary.applyStatus(),
                primary.effectiveFrom(),
                true,
                prepared.conversationId());
    }

    /*
     * **실패에도 걸린 시간을 남긴다** (#448).
     *
     * 성공 로그에만 `소요`가 있던 동안 «상한에 걸리고 있는가»를 알아내려면 요청 줄과 오류 줄의
     * 시각을 사람이 빼야 했고, 그래서 «상한 20초인데 사용자는 35~43초를 기다린다»가 한참 뒤에야
     * 드러났다. 세 로그가 같은 축(`발췌` · `이력` · `소요`)을 쓰면 **값을 바꾼 뒤 다시 재는 일이
     * 로그 한 번 긁는 일**이 된다 — 이 기능에 지표(Micrometer)가 없는 이유가 그것을 대신한다
     * (루트 AGENTS.md «관측성» — export 가 꺼져 있어 Kibana 가 유일한 계기판이다).
     */
    private static long elapsedMillis(Prepared prepared) {
        return prepared.timeline().elapsed();
    }

    /*
     * 빈 조각은 내보내지 않는다 — 판정에 붙들린 글자만 있었다는 뜻이라 이벤트를 만들 이유가 없다.
     *
     * **`첫송신`은 보낸 «뒤»에 찍는다** (#453) — 재려는 것이 «화면에 글자가 닿은 시각»이라서다.
     * 앞에서 찍으면 받는 쪽이 사라져 `delta`가 던지는 경우에도 보낸 것으로 남는다.
     */
    private void emit(AssistantAnswerSink sink, QueryTimeline timeline, String text) {
        if (text.isEmpty()) {
            return;
        }
        sink.delta(text);
        timeline.sent();
    }

    /*
     * 흘려보내는 도중의 실패.
     *
     * **보내는 쪽이 닫힌 것과 공급자가 실패한 것을 가른다** — 앞은 알릴 상대가 이미 없어 로그 한
     * 줄이고, 뒤는 화면이 «일시적으로 답할 수 없어요»를 그려야 하므로 오류 이벤트다. Reactor가
     * onNext 안의 예외를 감싸 올리므로 원인 사슬을 풀어 본다.
     */
    private void failed(Prepared prepared, AssistantAnswerSink sink, Throwable failure) {
        Throwable cause = Exceptions.unwrap(failure);
        if (closedByReceiver(cause)) {
            log.info("규정 도우미 스트림이 도중에 닫혔다 — mbrId={}", prepared.memberId());
            return;
        }
        /*
         * 공급자 장애·타임아웃·쿼터. **원문을 응답에 싣지 않는다** — 모델 SDK의 예외 문장에는
         * 요청 본문 일부가 섞여 나오고 그 본문이 곧 사용자의 질문이다(§11). 로그에는 남긴다.
         */
        log.error(
                "규정 도우미 모델 스트림이 실패했다 — 발췌={} 이력={}턴 검색={}ms 첫수신={}ms" + " 첫송신={}ms 소요={}ms",
                prepared.chunks().size(),
                prepared.history().size() / 2,
                prepared.timeline().retrieval(),
                prepared.timeline().firstChunk(),
                prepared.timeline().firstDelta(),
                elapsedMillis(prepared),
                cause);
        sink.failed(AssistantErrorCode.ASSISTANT_UPSTREAM_FAILED);
    }

    /*
     * Reactor는 `onNext` 안에서 난 예외를 제 나름대로 감싸 올린다(`Operators.onOperatorError`) —
     * `Exceptions.unwrap`이 아는 껍데기만 벗겨지므로 **원인 사슬까지 훑는다.** 놓치면 탭을 닫은
     * 요청 하나가 `ASSISTANT_UPSTREAM_FAILED` ERROR 로그를 남겨 «공급자 장애»로 보인다.
     */
    private boolean closedByReceiver(Throwable failure) {
        for (Throwable at = failure; at != null; at = at.getCause()) {
            if (at instanceof AssistantStreamClosedException) {
                return true;
            }
            if (at.getCause() == at) {
                break;
            }
        }
        return false;
    }

    /*
     * 볼 수 있는 판본을 **식별자 → 값**으로.
     *
     * **`@Transactional`을 걸지 않는다.** 질의가 하나뿐이라 리포지토리 자신의 트랜잭션으로
     * 충분하고, 서비스 메서드에 걸면 그 경계가 **모델 호출까지 감싸** Gemini 왕복 동안 Supabase
     * Free의 커넥션을 쥔다(ssccops#324 · 색인 워커가 트랜잭션을 셋으로 쪼갠 것과 같은 이유 ·
     * #400). 밖으로 나가는 것은 지연 로딩이 없는 값뿐이라 준영속 엔티티 문제도 없다.
     */
    private Map<Long, SearchableDocument> searchableDocuments() {
        Map<Long, SearchableDocument> searchable = new LinkedHashMap<>();
        for (RagDocumentEntity document : ragDocumentRepository.findSearchable()) {
            searchable.put(document.getId(), SearchableDocument.from(document));
        }
        return searchable;
    }

    /*
     * 지금 코퍼스가 답할 수 있는가 — **두 상태를 가르는 유일한 자리** (#449).
     *
     * **«검색 대상이 있는가»를 먼저 묻고, 없을 때만 전체 건수를 센다.** 조건(`INDEXED &&
     * EFFECTIVE`)을 옮겨 적은 두 번째 질의를 만들지 않는 것이 요점이다 — `findSearchable`이 그
     * 조건을 한 곳에 박아 둔 이유가 «한쪽만 넘기는 순간 새어 나간다»이고(리포지토리 주석), 세는
     * 질의를 따로 쓰면 그 조건이 두 벌이 되어 **화면의 빈 상태와 실제 검색 결과가 갈린다.**
     *
     * 건수 질의가 뒤에 있는 것은 그것이 **답할 수 없을 때만 필요한 값**이기 때문이다. `READY`인
     * 코퍼스에서는 부르지 않으므로 평소 경로의 질의 수가 늘지 않는다.
     *
     * ⚠️ **`count()`는 «행이 있는가»이지 «시행 가능한 문서가 있는가»가 아니다** — 내려둔
     * (`SUPERSEDED`) 문서만 남은 코퍼스도, 색인이 실패한 문서만 있는 코퍼스도 `NONE_EFFECTIVE`로
     * 묶인다. 그 넷을 가르지 않은 이유는 `AssistantCorpusState`에 있다. 삭제가 하드라
     * (ADR-0029) 이 수는 관리 화면의 «등록 문서» 카드와 같은 값이다(`RagCorpusSummaryResponse`).
     */
    private AssistantCorpusState corpusState() {
        if (!searchableDocuments().isEmpty()) {
            return AssistantCorpusState.READY;
        }
        return ragDocumentRepository.count() == 0
                ? AssistantCorpusState.EMPTY
                : AssistantCorpusState.NONE_EFFECTIVE;
    }

    /*
     * 검색 — **필터가 판본 조건 둘의 결과이고, 임계값은 유형별로 한 번 더 본다.**
     *
     * 저장소에는 가장 느슨한 임계값으로 긁는다(`searchThreshold`). 유형별 판정을 검색 안에서 할
     * 수 없기 때문인데(요청 하나에 임계값 하나다), 그 대신 돌아온 청크를 유형별로 다시 재는
     * 것은 안전하다 — 「덜 보여 준다」 방향이라 새어 나갈 것이 없다.
     *
     * ⚠️ **여기서 만들어진 목록의 순서가 곧 인용 번호다**(#447). 모델이 쓰는 `[3]`은 이 목록의
     * 세 번째이며, 프롬프트에 찍는 번호(`AssistantPrompt.user`)와 해석(`CitationVerifier`)이
     * 같은 목록을 본다 — 사이에서 걸러 내거나 다시 정렬하면 인용이 조용히 어긋난다.
     */
    private List<RetrievedChunk> retrieve(
            String question, Map<Long, SearchableDocument> searchable, RagChunkStore chunkStore) {

        SearchRequest search =
                SearchRequest.builder()
                        .query(question)
                        .topK(policy.getTopK())
                        .similarityThreshold(policy.searchThreshold())
                        .filterExpression(
                                new FilterExpressionBuilder()
                                        .in(
                                                RagChunkStore.RAG_DOCUMENT_ID_KEY,
                                                List.copyOf(searchable.keySet()))
                                        .build())
                        .build();

        List<RetrievedChunk> chunks = new ArrayList<>();
        for (Document found : chunkStore.search(search)) {
            Long ragDocId = RetrievedChunk.ragDocumentIdOf(found.getMetadata());
            SearchableDocument source = ragDocId == null ? null : searchable.get(ragDocId);
            if (source == null) {
                // 판본을 붙일 수 없는 청크 — 인용을 만들 수 없으므로 근거가 되지 못한다(클래스 주석)
                log.warn("검색 결과에 판본을 알 수 없는 청크가 섞여 있다 — ragDocId={}", ragDocId);
                continue;
            }
            RetrievedChunk chunk = new RetrievedChunk(found, source);
            if (chunk.score() >= policy.thresholdFor(source.type())) {
                chunks.add(chunk);
            }
        }
        return chunks;
    }

    /*
     * 프롬프트 조립 — **도구를 붙이지 않는다.** 인젝션이 성공해도 할 수 있는 것이 «이상한 답을
     * 한다» 뿐이라는 성질이 이 기능의 경계이며(§6.4), `.tools(...)`를 여기 들이는 순간 그 경계가
     * 사라진다.
     *
     * 시스템·사용자 텍스트에 변수를 넘기지 않으므로 Spring AI의 템플릿 렌더러를 **지나지
     * 않는다**(`DefaultChatClientUtils` — 변수 맵이 비면 렌더링을 건너뛴다). 질문에 `{`가 섞여도
     * 깨지지 않는 것이 그 덕이고, 여기에 `.param(...)`을 더하면 그 성질이 사라진다. 앞선 턴들도
     * `Message` 그대로 실려 같은 이유로 렌더링을 지나지 않는다.
     *
     * 순서는 **시스템 → 이력 → 이번 발췌·질문**이다(`.messages(...)`가 그 가운데에 들어간다 ·
     * #406). 이력이 비면 목록째 건너뛰므로 첫 질문의 프롬프트는 #403 그대로다.
     *
     * **한 번에 받는 길과 흘려보내는 길이 이 메서드를 함께 쓴다** — 갈라 두면 «스트리밍에서만
     * 다른 규칙으로 답하는» 상태가 조용히 성립한다.
     */
    private ChatClient.ChatClientRequestSpec prompt(Prepared prepared) {
        return prepared.chatClient()
                .prompt()
                .system(AssistantPrompt.SYSTEM)
                .messages(prepared.history())
                .user(AssistantPrompt.user(prepared.question(), prepared.chunks()));
    }

    /** 한 번에 받는 생성. 실패는 그대로 503이다 — 아직 아무것도 나가지 않았다 */
    private String generate(Prepared prepared) {
        try {
            String answer = prompt(prepared).call().content();
            return answer == null ? "" : answer;

        } catch (RuntimeException exception) {
            /*
             * 공급자 장애·타임아웃·쿼터. **원문을 응답에 싣지 않는다** — 모델 SDK의 예외 문장에는
             * 요청 본문 일부가 섞여 나오고 그 본문이 곧 사용자의 질문이다(§11). 로그에는 남긴다.
             */
            log.error(
                    "규정 도우미 모델 호출이 실패했다 — 발췌={} 이력={}턴 검색={}ms 소요={}ms",
                    prepared.chunks().size(),
                    prepared.history().size() / 2,
                    prepared.timeline().retrieval(),
                    elapsedMillis(prepared),
                    exception);
            throw new GeneralException(AssistantErrorCode.ASSISTANT_UPSTREAM_FAILED);
        }
    }

    /*
     * 거절 — **정해진 문구 · 빈 배열 · 판본 없음**(§6.3). 이유는 로그에만 남는다: 사용자에게
     * «모델이 근거 없는 답을 했습니다»라고 말할 이유가 없고, 화면이 할 일은 세 경우 모두 같다.
     *
     * **그래서 «검증기가 답을 버린 횟수»는 이 로그로만 센다** (#447). 지표(Micrometer)를 따로 달지
     * 않은 것은 dev·prod 모두 export 가 꺼져 있어(루트 AGENTS.md «관측성») 그 수가 아무 데도
     * 닿지 않기 때문이다 — 지금 그 수를 읽을 수 있는 곳은 Kibana 뿐이고, 사유가 문장에 그대로
     * 실려 있어 셀 수 있다.
     *
     * **대화 식별자는 싣고 이력에는 담지 않는다** — 화면은 이 값으로 이어 물어야 하지만(#406),
     * 「찾지 못했습니다」는 다음 턴이 기댈 맥락이 아니다.
     */
    private AssistantQueryResponse refuse(
            long memberId,
            String reason,
            int chunkCount,
            String conversationId,
            QueryTimeline timeline) {

        log.info(
                "규정 도우미 거절 — mbrId={} 사유={} 발췌={} 검색={}ms 소요={}ms",
                memberId,
                reason,
                chunkCount,
                timeline.retrieval(),
                timeline.elapsed());
        return AssistantQueryResponse.unanswered(AssistantPrompt.NO_EVIDENCE, conversationId);
    }

    /*
     * 질문 길이는 **용량 규칙이다**(§8.1) — 프롬프트 길이가 곧 힙과 모델 입력이다. 빈 질문은
     * `@NotBlank`가 이미 400으로 끊었고, 여기서 다시 보는 것은 공백만 있는 요청이 검색어로
     * 나가지 않게 하기 위해서다.
     */
    private String requireAskable(String question) {
        String asked = question == null ? "" : question.strip();
        if (asked.length() > policy.getMaxQuestionLength()) {
            throw new GeneralException(
                    AssistantErrorCode.ASSISTANT_QUESTION_TOO_LONG,
                    "질문은 %d자까지 쓸 수 있습니다.".formatted(policy.getMaxQuestionLength()));
        }
        return asked;
    }

    /** 키가 없어 배선이 서지 않았다 — 503. <b>플래그 off(404)와 갈린다</b>(운영자가 할 일이 있다) */
    private <T> T require(ObjectProvider<T> provider) {
        T bean = provider.getIfAvailable();
        if (bean == null) {
            throw new GeneralException(AssistantErrorCode.ASSISTANT_UNAVAILABLE);
        }
        return bean;
    }

    /**
     * 첫 바이트 전 판정이 끝난 질의 하나 (#447).
     *
     * <p>{@code refusal}이 있으면 <b>모델을 부르지 않는다</b> — 그 값이 곧 응답이고, 다른 칸은 비어 있다. 회원 엔티티를 들고 다니지 않는 것은 이
     * 값이 요청 스레드 밖에서 읽히기 때문이다(준영속 엔티티의 지연 로딩 필드를 거기서 건드리면 터진다 · {@code SearchableDocument}와 같은 판단).
     */
    private record Prepared(
            long memberId,
            String question,
            String conversationId,
            ChatClient chatClient,
            List<RetrievedChunk> chunks,
            List<Message> history,
            QueryTimeline timeline,
            AssistantQueryResponse refusal) {

        static Prepared refused(AssistantQueryResponse refusal) {
            return new Prepared(0, null, null, null, List.of(), List.of(), null, refusal);
        }
    }

    /*
     * 한 질의의 이정표 (#453).
     *
     * ══ 시각이지 구간이 아니다 ═════════════════════════════════════
     *
     * 값은 전부 **«요청 시작으로부터 몇 ms»**다. 구간으로 적으면 이정표를 하나 더할 때마다 앞 칸의
     * 뜻이 함께 바뀌어 옛 로그와 새 로그를 나란히 놓을 수 없는데, 시작점을 공유하면 칸을 더해도
     * 기존 칸이 그대로다 — `소요`(#448)가 이미 그 축이라 새 칸들이 거기에 붙는다.
     *
     * 읽는 쪽이 빼면 구간이 나온다:
     *
     * | 구간 | 무엇이 | 빼는 법 |
     * |---|---|---|
     * | **A** | DB 조회 · 질의 임베딩 왕복 · pgvector 검색. **여기까지 응답 헤더도 나가지 않는다** | `검색` |
     * | **B** | 모델이 첫 조각을 줄 때까지 — 연결 · 프롬프트 처리 · 사고(thinking) | `첫수신 - 검색` |
     * | C | 검증기가 첫 조각을 붙든 시간(닫히지 않은 `[`와 그 앞 공백 · #447) | `첫송신 - 첫수신` |
     *
     * **C 를 따로 재는 것은 그것이 6초의 설명이 못 된다는 것을 확인하기 위해서다** — 수십 ms 로
     * 나오면 그 자리를 다시 의심하지 않아도 되고, 크게 나오면 그때는 그것이 원인이다.
     *
     * ══ 없는 이정표는 -1 이다 ══════════════════════════════════════
     *
     * 일괄 경로(`/queries`)에는 `첫수신`·`첫송신`이 없고(조각이 없다), 검색 전에 끊긴 거절에는
     * `검색`이 없다. **0 을 쓰면 «곧바로 일어났다»와 구별되지 않는다** — 0ms 는 실제로 나올 수
     * 있는 값이다(임베딩 캐시가 맞은 검색).
     *
     * ══ 질문도 답변도 담지 않는다 ══════════════════════════════════
     *
     * 담는 것은 시각뿐이다(ADR-0024 · §11). 이 객체가 로그로 나가는 유일한 통로이므로 여기에
     * 본문을 실을 칸을 열지 말 것.
     *
     * ⚠️ **표시하는 스레드가 여럿이다** — `검색`은 요청 스레드, 조각 둘은 `boundedElastic`,
     * 읽는 쪽(실패·완료 로그)은 또 다를 수 있다. 그래서 필드가 `volatile`이다. 조각 표시가
     * 검사 후 대입이라 엄밀히는 경합이 남지만, 같은 구독을 한 스레드가 순서대로 흘려보내므로
     * (Reactive Streams 의 onNext 직렬성) 실제로 겹치지 않는다 — 겹치더라도 잃는 것은 몇 ms 다.
     */
    private static final class QueryTimeline {

        /** 그 이정표가 이 경로에 없다 — 0(«곧바로»)과 갈린다 */
        private static final long ABSENT = -1;

        private final Instant startedAt = Instant.now();

        private volatile Instant retrievedAt;

        private volatile Instant firstChunkAt;

        private volatile Instant firstDeltaAt;

        /** 검색이 끝났다 — 스트리밍에서는 이때 응답이 열린다(`SseEmitter`가 반환되는 시각) */
        void retrieved() {
            retrievedAt = Instant.now();
        }

        /** 모델의 첫 조각. <b>첫 번째만 남긴다</b> — 뒤 조각이 덮으면 이정표가 아니라 «마지막 조각»이 된다 */
        void received() {
            if (firstChunkAt == null) {
                firstChunkAt = Instant.now();
            }
        }

        /** 화면으로 나간 첫 조각. 검증기가 붙들었다면 {@link #received()}보다 늦다 */
        void sent() {
            if (firstDeltaAt == null) {
                firstDeltaAt = Instant.now();
            }
        }

        long retrieval() {
            return since(retrievedAt);
        }

        long firstChunk() {
            return since(firstChunkAt);
        }

        long firstDelta() {
            return since(firstDeltaAt);
        }

        long elapsed() {
            return Duration.between(startedAt, Instant.now()).toMillis();
        }

        private long since(Instant at) {
            return at == null ? ABSENT : Duration.between(startedAt, at).toMillis();
        }
    }
}
