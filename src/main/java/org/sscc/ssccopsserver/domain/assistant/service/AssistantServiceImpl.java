package org.sscc.ssccopsserver.domain.assistant.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.sscc.ssccopsserver.domain.assistant.code.error.AssistantErrorCode;
import org.sscc.ssccopsserver.domain.assistant.dto.AssistantQueryRequest;
import org.sscc.ssccopsserver.domain.assistant.dto.AssistantQueryResponse;
import org.sscc.ssccopsserver.domain.assistant.dto.AssistantSuggestionsResponse;
import org.sscc.ssccopsserver.domain.assistant.entity.RagDocumentEntity;
import org.sscc.ssccopsserver.domain.assistant.repository.RagDocumentRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/*
 * 질의 한 건 — 검색 → (근거가 있으면) 생성 → 인용 검증 (#403 · 기획안 §6).
 *
 * ══ 방어선이 셋이고 순서가 중요하다 ═════════════════════════════
 *
 *   1차  **임계값을 넘는 청크가 없으면 모델을 부르지 않는다.** 프롬프트의 «모르면 모른다고
 *        하라»는 지시를 모델은 종종 어기는데, **아예 부르지 않으면 어길 수 없다.**
 *   2차  프롬프트가 다섯 규칙을 건다(`AssistantPrompt`).
 *   3차  **모델이 단 인용을 실제 청크와 대조한다**(`CitationVerifier`). 통과한 것이 하나도
 *        없으면 그 답을 통째로 버리고 거절 문구를 내린다 — 출처 없는 규정 답변은 틀린 답보다
 *        나쁘다(§6.1).
 *
 * **거절이 이 기능의 가장 중요한 동작이다.** 운영진이 답변을 근거로 사람의 자격을 판단한다.
 *
 * ══ 그 앞에 레이트 리밋이 있다 ══════════════════════════════════
 *
 * 위 셋이 «무엇을 답할 것인가»의 방어선이라면 `AssistantRateLimiter`는 «얼마나 답할 것인가»의
 * 방어선이다(#404 · §11). 무료 쿼터가 API 키 단위의 공유 자원이라 한 사람의 루프가 전원의
 * 답변을 멈춘다 — 그래서 **모델을 부르기 전에** 429로 끊는다. 순서의 이유는 `query` 안에 있다.
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
 * `SearchableDocument`로 옮겨 담는다.
 *
 * ══ 남기지 않는 것 ═════════════════════════════════════════════
 *
 * **질문도 답변도 어디에도 저장하지 않는다**(§9 · §11 — 질의 로그 표를 두지 않았다). 로그에도
 * 싣지 않는다: 질문에는 사람 이름이 섞여 들어올 수 있고, 로그는 Kibana에 남는다(ADR-0024).
 * 남기는 것은 «누가·몇 개의 근거로·답했는가·얼마나 걸렸는가»다.
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
        assistantFeature.requireEnabled();

        /*
         * **거절의 순서가 곧 «무엇을 아껴야 하는가»의 순서다** — 뒤로 갈수록 값비싼 자원을
         * 건드린다. 한도(429)를 맨 뒤에 두는 것은 그 앞의 셋이 전부 **쿼터를 한 톨도 쓰지 않는
         * 거절**이기 때문이다: 기능이 꺼져 있거나(404), 질문이 상한을 넘었거나(413), 키가 없어
         * 배선이 서지 않은(503) 요청은 애초에 Gemini에 닿지 못하므로 그 사람의 한도를 깎을
         * 이유가 없다. 여기를 지난 요청만이 임베딩을 부른다.
         */
        String question = requireAskable(request.question());
        RagChunkStore chunkStore = require(ragChunkStore);
        ChatClient chatClient = require(assistantChatClient);
        rateLimiter.requireWithinQuota(member.getId());

        Instant startedAt = Instant.now();

        Map<Long, SearchableDocument> searchable = searchableDocuments();
        if (searchable.isEmpty()) {
            /*
             * **새 환경의 기본 상태가 여기다**(§12.5 — 코퍼스는 업로드로만 들어온다). 시행 중인
             * 문서가 하나도 없으면 검색할 것이 없으므로 임베딩조차 부르지 않는다.
             */
            return refuse(member, "시행 중인 규정 문서가 없다", 0);
        }

        List<RetrievedChunk> chunks = retrieve(question, searchable, chunkStore);
        if (chunks.isEmpty()) {
            return refuse(member, "임계값을 넘는 청크가 없다", 0);
        }

        CitationVerifier.Verified verified =
                citationVerifier.verify(generate(chatClient, question, chunks), chunks);
        if (verified.citations().isEmpty()) {
            /*
             * 모델이 답은 했는데 **검증을 통과한 인용이 하나도 없다.** 근거 없는 규정 답변을
             * 내보내지 않는다 — 사용자에게는 「찾지 못했다」와 같은 문구이고(화면이 할 일이
             * 같다), 둘을 가르는 값은 이 로그에만 남는다.
             */
            return refuse(member, "모델의 답에서 검증을 통과한 인용이 없다", chunks.size());
        }

        SearchableDocument primary = verified.citations().get(0).source();
        log.info(
                "규정 도우미 답변 — mbrId={} 발췌={} 인용={} 버린인용={} 기준판본={}(v{}) 소요={}ms",
                member.getId(),
                chunks.size(),
                verified.citations().size(),
                verified.dropped(),
                primary.documentCode(),
                primary.version(),
                Duration.between(startedAt, Instant.now()).toMillis());

        return new AssistantQueryResponse(
                verified.answer(),
                verified.responses(),
                primary.applyStatus(),
                primary.effectiveFrom(),
                true);
    }

    @Override
    public AssistantSuggestionsResponse suggestions() {
        assistantFeature.requireEnabled();

        Set<String> documentCodes =
                searchableDocuments().values().stream()
                        .map(SearchableDocument::documentCode)
                        .collect(Collectors.toSet());

        return new AssistantSuggestionsResponse(
                assistantSuggestions.forDocumentCodes(documentCodes));
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
     * 검색 — **필터가 판본 조건 둘의 결과이고, 임계값은 유형별로 한 번 더 본다.**
     *
     * 저장소에는 가장 느슨한 임계값으로 긁는다(`searchThreshold`). 유형별 판정을 검색 안에서 할
     * 수 없기 때문인데(요청 하나에 임계값 하나다), 그 대신 돌아온 청크를 유형별로 다시 재는
     * 것은 안전하다 — 「덜 보여 준다」 방향이라 새어 나갈 것이 없다.
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
     * 생성 — **도구를 붙이지 않는다.** 인젝션이 성공해도 할 수 있는 것이 «이상한 답을 한다»
     * 뿐이라는 성질이 이 기능의 경계이며(§6.4), `.tools(...)`를 여기 들이는 순간 그 경계가
     * 사라진다.
     *
     * 시스템·사용자 텍스트에 변수를 넘기지 않으므로 Spring AI의 템플릿 렌더러를 **지나지
     * 않는다**(`DefaultChatClientUtils` — 변수 맵이 비면 렌더링을 건너뛴다). 질문에 `{`가 섞여도
     * 깨지지 않는 것이 그 덕이고, 여기에 `.param(...)`을 더하면 그 성질이 사라진다.
     */
    private String generate(ChatClient chatClient, String question, List<RetrievedChunk> chunks) {
        try {
            String answer =
                    chatClient
                            .prompt()
                            .system(AssistantPrompt.SYSTEM)
                            .user(AssistantPrompt.user(question, chunks))
                            .call()
                            .content();
            return answer == null ? "" : answer.strip();

        } catch (RuntimeException exception) {
            /*
             * 공급자 장애·타임아웃·쿼터. **원문을 응답에 싣지 않는다** — 모델 SDK의 예외 문장에는
             * 요청 본문 일부가 섞여 나오고 그 본문이 곧 사용자의 질문이다(§11). 로그에는 남긴다.
             */
            log.error("규정 도우미 모델 호출이 실패했다 — 발췌={}", chunks.size(), exception);
            throw new GeneralException(AssistantErrorCode.ASSISTANT_UPSTREAM_FAILED);
        }
    }

    /*
     * 거절 — **정해진 문구 · 빈 배열 · 판본 없음**(§6.3). 이유는 로그에만 남는다: 사용자에게
     * «모델이 근거 없는 답을 했습니다»라고 말할 이유가 없고, 화면이 할 일은 세 경우 모두 같다.
     */
    private AssistantQueryResponse refuse(MemberEntity member, String reason, int chunkCount) {
        log.info("규정 도우미 거절 — mbrId={} 사유={} 발췌={}", member.getId(), reason, chunkCount);
        return AssistantQueryResponse.unanswered(AssistantPrompt.NO_EVIDENCE);
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
}
