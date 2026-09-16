package org.sscc.ssccopsserver.domain.assistant.service;

import static java.time.ZoneOffset.UTC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.sscc.ssccopsserver.domain.assistant.code.AssistantCorpusState;
import org.sscc.ssccopsserver.domain.assistant.code.RagApplyStatus;
import org.sscc.ssccopsserver.domain.assistant.code.RagDocumentType;
import org.sscc.ssccopsserver.domain.assistant.code.error.AssistantErrorCode;
import org.sscc.ssccopsserver.domain.assistant.dto.AssistantQueryRequest;
import org.sscc.ssccopsserver.domain.assistant.dto.AssistantQueryResponse;
import org.sscc.ssccopsserver.domain.assistant.dto.AssistantSuggestionsResponse;
import org.sscc.ssccopsserver.domain.assistant.entity.RagDocumentEntity;
import org.sscc.ssccopsserver.domain.assistant.repository.RagDocumentRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.code.error.ErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;
import org.sscc.ssccopsserver.support.LogCapture;

import reactor.core.publisher.Flux;

/*
 * 질의 한 건의 규칙 — **거절 · 검색 필터 둘 · 인용 해석 · 스트리밍** (#403 · #447 · 기획안 §6 · §14.2).
 *
 * ══ 왜 컨텍스트 없이 보는가 ═════════════════════════════════════
 *
 * 확인하려는 것이 배선이 아니라 **순서와 거절**이다. 모델을 부르는 자리를 스텁으로 두면
 * «부르지 않았다»를 그대로 셀 수 있고(1차 방어선이 그 사실이다), 검색 요청을 잡아 필터를 열어
 * 볼 수 있다 — 스프링을 띄우면 그 둘 다 흐려진다. 실제 모델 품질은 골든셋(#405)의 몫이며
 * CI에 넣지 않는다(외부 의존이고 비결정적이다).
 *
 * ══ 스트리밍도 여기서 본다 (#447) ══════════════════════════════
 *
 * 확인하는 것은 **순서**다 — 거절의 계단이 첫 조각보다 앞에서 끝나는가, 흘려보낸 조각을 이어
 * 붙이면 한 번에 받은 답과 같은가, 도중에 실패하면 예외가 아니라 오류 이벤트로 가는가. SSE
 * 자체(이벤트 이름·봉투 없음)는 컨트롤러 테스트가 본다.
 */
class AssistantServiceImplTest {

    private final RagDocumentRepository ragDocumentRepository = mock(RagDocumentRepository.class);
    private final RagChunkStore ragChunkStore = mock(RagChunkStore.class);
    private final RecordingChatModel chatModel = new RecordingChatModel();

    /** 한도가 세는 축이 회원 식별자라 목도 그 값을 들고 있어야 한다 (#404) */
    private final MemberEntity member = member(7L);

    private final AssistantQueryPolicy policy =
            new AssistantQueryPolicy(8, 0.5, null, null, 1000, 200);

    /*
     * 대화는 **진짜를 쓴다** — 우리 힙에 있고 가벼우며, 확인하려는 것이 «이력이 프롬프트의
     * 어디에 실리는가»라 목으로 두면 그 자리가 보이지 않는다(#406).
     */
    private final AssistantConversations conversations =
            new AssistantConversations(
                    MessageWindowChatMemory.builder()
                            .chatMemoryRepository(
                                    new AssistantMemoryStore(
                                            500,
                                            Duration.ofHours(24),
                                            Clock.fixed(
                                                    Instant.parse("2026-09-15T01:00:00Z"), UTC)))
                            .maxMessages(40)
                            .build());

    private final AssistantServiceImpl service = service(true);

    // ------------------------------------------------------------------ 1차 방어선

    /*
     * **시행 중인 문서가 하나도 없으면 모델도 임베딩도 부르지 않는다.**
     *
     * 코퍼스가 비어 있는 것이 **새 환경의 기본 상태**이므로(§12.5) 이 경로가 첫 배포의 정상
     * 동작이다 — 검색조차 하지 않는 것은 부를 임베딩이 곧 쿼터이기 때문이다.
     */
    @Test
    void refusesWithoutCallingAnythingWhenNoDocumentIsInForce() {
        when(ragDocumentRepository.findSearchable()).thenReturn(List.of());

        AssistantQueryResponse response = service.query(ask("정회원 승격 조건은?"), member);

        assertThat(response.answered()).isFalse();
        assertThat(response.answer()).isEqualTo(AssistantPrompt.NO_EVIDENCE);
        assertThat(response.citations()).as("빈 배열이지 null이 아니다").isEmpty();
        assertThat(response.applyStatus()).isNull();
        assertThat(response.effectiveDate()).isNull();
        assertThat(chatModel.calls).isZero();
        verify(ragChunkStore, never()).search(any());
    }

    /*
     * **임계값을 넘는 청크가 없으면 모델을 부르지 않는다** — 1차 방어선(§6.1).
     *
     * 프롬프트의 «모르면 모른다고 하라»는 2차이고 모델은 그 지시를 종종 어긴다. **아예 부르지
     * 않으면 어길 수 없다.**
     */
    @Test
    void refusesWithoutCallingTheModelWhenNothingClearsTheThreshold() {
        searchable(regulation());
        when(ragChunkStore.search(any())).thenReturn(List.of());

        assertThat(service.query(ask("주차장 이용 규정은?"), member).answered()).isFalse();
        assertThat(chatModel.calls).isZero();
    }

    /*
     * 저장소가 돌려준 청크라도 **유형별 임계값 아래면 근거가 되지 못한다**.
     *
     * 검색에는 가장 느슨한 값으로 긁고 유형별 판정은 그 뒤에 한다 — 조 단위 청크(450자)와 고정
     * 길이 청크(600자)는 점수 분포가 같지 않기 때문이다(`AssistantQueryPolicy`).
     */
    @Test
    void appliesThePerTypeThresholdAfterTheSearch() {
        AssistantServiceImpl strictOnGeneric =
                new AssistantServiceImpl(
                        new AssistantFeature(true),
                        new AssistantQueryPolicy(8, 0.3, null, 0.9, 1000, 200),
                        limiter(5),
                        new AssistantSuggestions(),
                        new CitationVerifier(policy),
                        conversations,
                        ragDocumentRepository,
                        provider(ragChunkStore),
                        provider(ChatClient.builder(chatModel).build()));

        searchable(guideline());
        when(ragChunkStore.search(any())).thenReturn(List.of(pageChunk(12, 0.5)));

        assertThat(strictOnGeneric.query(ask("정산 기한은?"), member).answered())
                .as("0.5는 평문 임계값 0.9에 못 미친다")
                .isFalse();
        assertThat(chatModel.calls).isZero();
    }

    // ------------------------------------------------------------------ 검색 필터 둘

    /*
     * **검색 조건이 «조회 뒤 if»가 아니라 필터다.** 필터에 실리는 식별자는 `INDEXED &&
     * EFFECTIVE`를 함께 건 질의(`findSearchable`)에서 나온 것뿐이다 — 하나라도 빠지면 의결 전
     * 개정안이 시행 중인 회칙 행세를 한다.
     */
    @Test
    void filtersTheSearchByTheVersionsThatTheQuerySelected() {
        searchable(regulation(), guideline());
        when(ragChunkStore.search(any())).thenReturn(List.of(articleChunk(7, 0.8)));
        chatModel.answer = "정회원은 총회의 동의가 필요합니다. [1]";

        service.query(ask("정회원 승격 조건은?"), member);

        ArgumentCaptor<SearchRequest> request = ArgumentCaptor.forClass(SearchRequest.class);
        verify(ragChunkStore).search(request.capture());

        assertThat(request.getValue().getTopK()).isEqualTo(8);
        assertThat(request.getValue().getSimilarityThreshold()).isEqualTo(0.5);
        assertThat(request.getValue().hasFilterExpression()).isTrue();
        assertThat(request.getValue().getFilterExpression().toString())
                .as("판본 조건 둘의 결과가 그대로 필터가 된다")
                .contains(RagChunkStore.RAG_DOCUMENT_ID_KEY)
                .contains("1")
                .contains("2");
    }

    /*
     * 필터를 지나 온 청크라도 **판본을 붙일 수 없으면 근거가 되지 못한다**.
     *
     * 고아 청크(색인 중 삭제가 남길 수 있다 · #401)와 필터를 무시하는 저장소를 함께 막는다 —
     * 필터의 대체가 아니라 «인용을 만들 수 없는 청크는 인용할 수 없다»는 사실의 표현이다.
     */
    @Test
    void ignoresChunksWhoseVersionIsUnknown() {
        searchable(regulation());
        when(ragChunkStore.search(any()))
                .thenReturn(
                        List.of(chunk(Map.of(RagChunkStore.RAG_DOCUMENT_ID_KEY, 99L), "고아", 0.9)));

        assertThat(service.query(ask("정회원 승격 조건은?"), member).answered()).isFalse();
        assertThat(chatModel.calls).isZero();
    }

    // ------------------------------------------------------------------ 생성과 검증

    /* 근거가 있으면 답하고, 배지의 재료(판본 상태·시행일)가 **첫 인용의 판본**에서 온다 */
    @Test
    void answersWithVerifiedCitationsAndTheVersionBadge() {
        searchable(regulation());
        when(ragChunkStore.search(any())).thenReturn(List.of(articleChunk(7, 0.8)));
        chatModel.answer = "정회원 승격은 총회의 동의가 필요합니다. [1]";

        AssistantQueryResponse response = service.query(ask("정회원 승격 조건은?"), member);

        assertThat(response.answered()).isTrue();
        assertThat(response.answer()).isEqualTo("정회원 승격은 총회의 동의가 필요합니다. [1]");
        assertThat(response.citations()).hasSize(1);
        assertThat(response.citations().get(0).ref()).as("본문의 [1]과 짝이다").isEqualTo(1);
        assertThat(response.citations().get(0).marker()).as("표기는 서버가 붙인다").isEqualTo("제7조");
        assertThat(response.citations().get(0).article()).isEqualTo("제7조 (회원의 구분)");
        assertThat(response.citations().get(0).clause()).as("번호 참조에는 항 정보가 없다").isNull();
        assertThat(response.applyStatus()).isEqualTo(RagApplyStatus.EFFECTIVE);
        assertThat(response.effectiveDate()).isEqualTo(LocalDate.of(2026, 3, 24));
    }

    /*
     * **검증을 통과한 인용이 하나도 없으면 그 답을 통째로 버린다** — 3차 방어선(§6.1).
     *
     * 출처 없는 규정 답변은 틀린 답보다 나쁘다. 사용자에게는 「찾지 못했다」와 같은 문구인데,
     * 화면이 할 일이 같고 «모델이 근거 없는 답을 했습니다»는 말할 이유가 없기 때문이다.
     */
    @Test
    void throwsAwayAnAnswerThatHasNoVerifiableCitation() {
        searchable(regulation());
        when(ragChunkStore.search(any())).thenReturn(List.of(articleChunk(7, 0.8)));
        chatModel.answer = "발췌를 하나도 가리키지 않는 답입니다.";

        AssistantQueryResponse response = service.query(ask("정회원 승격 조건은?"), member);

        assertThat(response.answered()).isFalse();
        assertThat(response.answer()).isEqualTo(AssistantPrompt.NO_EVIDENCE);
        assertThat(response.citations()).isEmpty();
        assertThat(chatModel.calls).as("모델은 불렀지만 그 답을 쓰지 않았다").isEqualTo(1);
    }

    /*
     * ⚠️ **인용을 달았어도 모델이 «근거 없음»을 알렸으면 거절이다** (#455).
     *
     * 이 고장이 실제로 난 모양은 «규정 문서에서 … 근거를 찾지 못했습니다 [1][2][3][4][5]»였다 —
     * 모델이 규칙을 어긴 것이 아니라 「근거가 없다」도 주장이라 출처를 붙인 것이고, 인용 수만
     * 보던 판정이 그것을 **답변으로 셌다.** 화면에는 판본 배지와 인용 카드 다섯 장이 달린
     * 「찾지 못했습니다」가 그려졌다.
     *
     * 그래서 표식이 인용을 이긴다. 사용자가 읽는 것은 모델의 거절 문장이 아니라 **서버의 안내
     * 문구**이고, 그 둘이 갈려 있어야 «더 구체적으로 적어 주세요»가 함께 나간다.
     */
    @Test
    void refusesWhenTheModelFlagsNoEvidenceEvenThoughItCitedExcerpts() {
        searchable(regulation());
        when(ragChunkStore.search(any())).thenReturn(List.of(articleChunk(7, 0.8)));
        chatModel.answer = "[근거없음] 이번 발췌에는 제3조의 조문 내용이 포함되어 있지 않습니다 [1]";

        AssistantQueryResponse response = service.query(ask("회칙 제3조의 내용을 그대로 인용해줘"), member);

        assertThat(response.answered()).isFalse();
        assertThat(response.answer()).isEqualTo(AssistantPrompt.NO_EVIDENCE);
        assertThat(response.citations()).as("거절에 인용 카드가 딸리지 않는다").isEmpty();
        assertThat(response.applyStatus()).as("기댄 판본이 없으므로 배지도 없다").isNull();
        assertThat(response.effectiveDate()).isNull();
        assertThat(conversations.history(response.conversationId()))
                .as("거절은 다음 턴의 맥락이 되지 않는다")
                .isEmpty();
    }

    /*
     * 같은 거절이 **스트리밍에서는 조각 없이** 온다 (#455 · #447).
     *
     * 표식이 첫 줄이므로 화면에 글자가 닿기 전에 판정이 끝난다 — 임계값 거절과 같은 모양(`delta`
     * 없이 `done` 하나)이고, 그래서 «근거 없음»으로 표시할 문장이 아니라 **정해진 안내 문구**가
     * 나간다. 이것이 `ungrounded`와 갈리는 지점이다.
     */
    @Test
    void streamsNothingAndSendsTheGuidanceWhenTheModelFlagsNoEvidence() {
        searchable(regulation());
        when(ragChunkStore.search(any())).thenReturn(List.of(articleChunk(7, 0.8)));
        chatModel.answer = "[근거없음]";

        RecordingSink sink = new RecordingSink();
        service.queryStreaming(ask("회칙 제3조의 내용을 그대로 인용해줘"), member, sink);
        sink.await();

        assertThat(sink.text()).as("표식은 화면에 닿지 않는다").isEmpty();
        assertThat(sink.done.answered()).isFalse();
        assertThat(sink.done.answer()).isEqualTo(AssistantPrompt.NO_EVIDENCE);
        assertThat(sink.done.citations()).isEmpty();
        assertThat(sink.done.applyStatus()).isNull();
    }

    /* 셋을 가르는 값은 로그에만 있다 — 표식 거절은 «모델이 규칙을 지킨 것»이라 프롬프트를 다시 볼 일이 없다 */
    @Test
    void tellsTheTwoGroundingFailuresApartInTheLogOnly() {
        searchable(regulation());
        when(ragChunkStore.search(any())).thenReturn(List.of(articleChunk(7, 0.8)));

        try (LogCapture logs = LogCapture.of(AssistantServiceImpl.class)) {
            chatModel.answer = "[근거없음]";
            service.query(ask("회칙 제3조의 내용을 그대로 인용해줘"), member);
            chatModel.answer = "발췌를 하나도 가리키지 않는 답입니다.";
            service.query(ask("정회원 승격 조건은?"), member);

            List<String> refusals =
                    logs.infoMessages().stream()
                            .filter(line -> line.startsWith("규정 도우미 거절"))
                            .toList();

            assertThat(refusals).hasSize(2);
            assertThat(refusals.get(0)).contains("모델이 근거 없음을 표식으로 알렸다");
            assertThat(refusals.get(1)).contains("모델의 답에서 검증을 통과한 인용이 없다");
        }
    }

    /*
     * 프롬프트가 **발췌와 질문을 나눠 싣고 회원 정보를 싣지 않는다** (§6.4 · §11).
     *
     * 개인정보를 넣지 않는 것은 무료 티어의 입력이 제품 개선에 쓰일 수 있기 때문이고, 블록을
     * 나누는 것은 도구 호출을 붙이지 않는 것과 함께 인젝션 완화에 남은 층이다.
     */
    @Test
    void putsTheExcerptsAndTheQuestionInSeparateBlocksAndNoMemberData() {
        when(member.getName()).thenReturn("이지훈");
        searchable(regulation());
        when(ragChunkStore.search(any())).thenReturn(List.of(articleChunk(7, 0.8)));
        chatModel.answer = "[1]";

        service.query(ask("정회원 승격 조건은?"), member);

        String system = chatModel.prompt.getInstructions().get(0).getText();
        String user = chatModel.prompt.getInstructions().get(1).getText();

        assertThat(system).contains("문서 발췌").contains("읽기 전용").contains("3~5문장");
        assertThat(user)
                .contains("[문서 발췌]")
                .as("발췌마다 번호를 찍는 것이 인용 계약의 전부다 — 조 표기는 적어 주지 않는다(#447)")
                .contains("--- 발췌 1 ---")
                .doesNotContain("인용 표기")
                .contains("[사용자 질문]\n정회원 승격 조건은?");
        assertThat(user).doesNotContain("이지훈");
        assertThat(chatModel.prompt.getOptions())
                .as("도구를 붙이지 않는다 — 인젝션이 성공해도 할 수 있는 것이 «이상한 답»뿐이라는 성질이 이 기능의 경계다")
                .isNotInstanceOf(ToolCallingChatOptions.class);
    }

    // ------------------------------------------------------------------ 거절이 아닌 오류

    /* 질문 길이는 용량 규칙이다(§8.1) — 413이며 `@Valid`의 400과 갈린다 */
    @Test
    void rejectsAQuestionLongerThanTheLimit() {
        assertThatThrownBy(() -> service.query(ask("가".repeat(1001)), member))
                .isInstanceOf(GeneralException.class)
                .hasMessageContaining("1000자");

        verify(ragDocumentRepository, never()).findSearchable();
    }

    /* 키가 없어 배선이 서지 않았다 — 503. **플래그 off(404)와 갈린다**(운영자가 할 일이 있다) */
    @Test
    void reportsUnavailableWhenTheWiringIsMissing() {
        AssistantServiceImpl unwired =
                new AssistantServiceImpl(
                        new AssistantFeature(true),
                        policy,
                        limiter(5),
                        new AssistantSuggestions(),
                        new CitationVerifier(policy),
                        conversations,
                        ragDocumentRepository,
                        provider(null),
                        provider(null));

        assertThatThrownBy(() -> unwired.query(ask("정회원 승격 조건은?"), member))
                .isInstanceOf(GeneralException.class)
                .hasMessageContaining("사용할 수 없습니다");
    }

    /* 모델 호출 실패는 503이고 **원문을 응답에 싣지 않는다** — 그 문장에 질문이 섞여 나온다(§11) */
    @Test
    void translatesAModelFailureIntoOurOwnMessage() {
        searchable(regulation());
        when(ragChunkStore.search(any())).thenReturn(List.of(articleChunk(7, 0.8)));
        chatModel.failure = new IllegalStateException("429 quota exceeded for 정회원 승격 조건은?");

        assertThatThrownBy(() -> service.query(ask("정회원 승격 조건은?"), member))
                .isInstanceOf(GeneralException.class)
                .hasMessage("지금은 답변을 만들 수 없습니다.");
    }

    /* 기능 플래그가 꺼져 있으면 404다 — 아무것도 묻지 않는다 */
    @Test
    void isNotFoundWhileTheAssistantIsDisabled() {
        assertThatThrownBy(() -> service(false).query(ask("정회원 승격 조건은?"), member))
                .isInstanceOf(GeneralException.class)
                .hasMessageContaining("비활성화");

        verify(ragDocumentRepository, never()).findSearchable();
    }

    // ------------------------------------------------------------------ 레이트 리밋

    /*
     * **한도를 넘으면 429이고 아무것도 묻지 않는다** (#404 · §11).
     *
     * 세는 것이 «답한 질의»가 아니라 «받아들인 질의»라는 사실이 여기 드러난다 — 첫 질의는
     * 코퍼스가 비어 거절로 끝났는데도 한도를 한 칸 썼다. 거절도 질문 임베딩을 부르고, 무료
     * 쿼터는 그 호출 단위로 닳는다.
     */
    @Test
    void refusesWithTooManyRequestsOnceTheMemberHasSpentTheMinuteQuota() {
        AssistantServiceImpl limited = service(true, limiter(1));
        when(ragDocumentRepository.findSearchable()).thenReturn(List.of());

        assertThat(limited.query(ask("정회원 승격 조건은?"), member).answered()).isFalse();

        assertThatThrownBy(() -> limited.query(ask("정회원 승격 조건은?"), member))
                .isInstanceOf(GeneralException.class)
                .hasMessageContaining("1분에 1번");

        verify(ragDocumentRepository, times(1)).findSearchable();
    }

    /*
     * **모델에 닿지 못하는 거절은 한도를 쓰지 않는다** — 그 앞의 셋(404 · 413 · 503)이 그렇다.
     *
     * 한도가 지키는 것은 쿼터이고, 질문이 너무 길어 되돌려보낸 요청은 쿼터를 한 톨도 쓰지
     * 않았다. 순서를 뒤집으면 오타 한 번이 그 사람의 한 칸을 먹는다 — 여기서는 413 뒤에도
     * 한 칸이 남아 있음을 실제 질의로 확인한다.
     */
    @Test
    void doesNotSpendQuotaOnRequestsThatNeverReachTheModel() {
        AssistantServiceImpl limited = service(true, limiter(1));
        when(ragDocumentRepository.findSearchable()).thenReturn(List.of());

        assertThatThrownBy(() -> limited.query(ask("가".repeat(1001)), member))
                .isInstanceOf(GeneralException.class)
                .hasMessageContaining("1000자");

        assertThat(limited.query(ask("정회원 승격 조건은?"), member).answered())
                .as("413은 한 칸도 쓰지 않았으므로 아직 물을 수 있다")
                .isFalse();
    }

    /* 한도는 **회원별**이다 — 한 사람의 연타가 남의 질의를 막지 않는다(IP당이 아닌 이유와 같은 축) */
    @Test
    void countsSeparatelyForEachMember() {
        AssistantServiceImpl limited = service(true, limiter(1));
        when(ragDocumentRepository.findSearchable()).thenReturn(List.of());

        limited.query(ask("정회원 승격 조건은?"), member);

        assertThat(limited.query(ask("정회원 승격 조건은?"), member(99L)).answered()).isFalse();
    }

    // ------------------------------------------------------------------ 대화 (#406)

    /*
     * **식별자는 서버가 발급하고 거절에도 실린다** (§7.4).
     *
     * 근거를 찾지 못한 첫 질문 뒤에 다시 묻는 것이 이 기능의 흔한 사용이라, 거절이 식별자를
     * 빠뜨리면 그 다음 질문이 새 대화로 시작된다.
     */
    @Test
    void issuesAConversationIdAndCarriesItBackEvenOnARefusal() {
        when(ragDocumentRepository.findSearchable()).thenReturn(List.of());

        AssistantQueryResponse refused = service.query(ask("정회원 승격 조건은?"), member);

        assertThat(refused.answered()).isFalse();
        assertThat(refused.conversationId()).startsWith("7:");
    }

    /*
     * **앞선 턴이 프롬프트의 가운데에 들어가고 검색어에는 들어가지 않는다** (#406).
     *
     * 순서는 시스템 → 이력 → 이번 발췌·질문이며, 검색은 **언제나 이번 질문 하나**로 한다 —
     * 이전 질문을 검색어에 이어 붙이면 임베딩이 두 주제 사이로 끌려가 맞는 청크가 임계값 아래로
     * 내려가는데, 그 실패가 «근거를 찾지 못했다»로만 보인다(`AssistantConversations`).
     */
    @Test
    void carriesTheEarlierTurnsIntoThePromptButNeverIntoTheSearch() {
        searchable(regulation());
        when(ragChunkStore.search(any())).thenReturn(List.of(articleChunk(7, 0.8)));
        chatModel.answer = "정회원은 총회의 동의가 필요합니다. [1]";

        String conversationId = service.query(ask("정회원 승격 조건은?"), member).conversationId();
        service.query(ask("그럼 준회원은요?", conversationId), member);

        List<Message> prompt = chatModel.prompt.getInstructions();
        assertThat(prompt).hasSize(4);
        assertThat(prompt.get(0).getMessageType()).isEqualTo(MessageType.SYSTEM);
        assertThat(prompt.get(1).getMessageType()).isEqualTo(MessageType.USER);
        assertThat(prompt.get(1).getText()).as("앞선 질문은 발췌 없이 그대로 남는다").isEqualTo("정회원 승격 조건은?");
        assertThat(prompt.get(2).getMessageType()).isEqualTo(MessageType.ASSISTANT);
        assertThat(prompt.get(2).getText()).isEqualTo("정회원은 총회의 동의가 필요합니다. [1]");
        assertThat(prompt.get(3).getText()).contains("[문서 발췌]").contains("그럼 준회원은요?");

        ArgumentCaptor<SearchRequest> requests = ArgumentCaptor.forClass(SearchRequest.class);
        verify(ragChunkStore, times(2)).search(requests.capture());
        assertThat(requests.getAllValues().get(1).getQuery())
                .as("검색어는 이번 질문 하나다")
                .isEqualTo("그럼 준회원은요?");
    }

    /*
     * **거절은 이력에 담기지 않는다.** 「찾지 못했습니다」는 이어 갈 맥락이 아니고, 담으면 20턴
     * 창을 차지하는 데다 모델에게 «이 대화에서는 이렇게 답한다»는 본보기가 된다.
     */
    @Test
    void remembersOnlyTheTurnsItActuallyAnswered() {
        searchable(regulation());
        when(ragChunkStore.search(any())).thenReturn(List.of());

        String conversationId = service.query(ask("주차장 이용 규정은?"), member).conversationId();

        when(ragChunkStore.search(any())).thenReturn(List.of(articleChunk(7, 0.8)));
        chatModel.answer = "정회원은 총회의 동의가 필요합니다. [1]";
        service.query(ask("정회원 승격 조건은?", conversationId), member);

        assertThat(chatModel.prompt.getInstructions())
                .as("거절로 끝난 첫 질문은 맥락에 없다 — 시스템과 이번 질문뿐이다")
                .hasSize(2);

        service.query(ask("그럼 준회원은요?", conversationId), member);
        assertThat(conversations.history(conversationId)).as("답한 턴 둘만 남는다").hasSize(4);
    }

    /*
     * **남의 대화는 이어 갈 수 없고, 그 거절은 한도를 쓰지 않는다** (§7.4).
     *
     * 판정이 레이트 리밋 앞에 있는 이유가 여기 드러난다 — 되돌려보낸 요청은 Gemini에 닿지
     * 못했으므로 그 사람의 한 칸을 깎을 이유가 없다(413이 한 칸도 쓰지 않는 것과 같은 줄기).
     */
    @Test
    void refusesSomeoneElsesConversationWithoutSpendingQuota() {
        AssistantServiceImpl limited = service(true, limiter(1));
        when(ragDocumentRepository.findSearchable()).thenReturn(List.of());

        assertThatThrownBy(
                        () -> limited.query(ask("정회원 승격 조건은?", "9:" + UUID.randomUUID()), member))
                .isInstanceOf(GeneralException.class)
                .hasMessageContaining("이어 갈 수 없습니다");

        assertThat(limited.query(ask("정회원 승격 조건은?"), member).answered())
                .as("403은 한 칸도 쓰지 않았으므로 아직 물을 수 있다")
                .isFalse();
    }

    /* 초기화 — 지운 뒤에는 맥락 없이 처음부터 답한다(패널의 `↺` · §13.1) */
    @Test
    void clearsTheConversationOnRequest() {
        searchable(regulation());
        when(ragChunkStore.search(any())).thenReturn(List.of(articleChunk(7, 0.8)));
        chatModel.answer = "정회원은 총회의 동의가 필요합니다. [1]";

        String conversationId = service.query(ask("정회원 승격 조건은?"), member).conversationId();
        service.clearConversation(conversationId, member);

        service.query(ask("그럼 준회원은요?", conversationId), member);

        assertThat(chatModel.prompt.getInstructions()).as("지운 뒤에는 시스템과 이번 질문뿐이다").hasSize(2);
    }

    /* 기능이 꺼져 있으면 초기화도 404다 — 질의와 같은 계단 */
    @Test
    void doesNotClearWhileTheAssistantIsDisabled() {
        assertThatThrownBy(() -> service(false).clearConversation("7:" + UUID.randomUUID(), member))
                .isInstanceOf(GeneralException.class)
                .hasMessageContaining("비활성화");
    }

    // ------------------------------------------------------------------ 추천 질문

    /*
     * 추천 질문은 **지금 검색 대상인 문서에만 매인다**(§13.3) — 답할 수 없으면 빈 목록이고 그것이
     * 새 환경의 정상 상태다.
     *
     * **그리고 «왜 비었는가»가 같은 응답에 실린다**(#449). 셋을 함께 보는 것은 화면이 이 값으로
     * 빈 상태 문구를 가르기 때문이고, 순서가 곧 **운영진이 이 기능을 처음 쓰며 지나는 순서**다 —
     * 아무것도 없다 → 올렸다(`DRAFT`) → 시행을 눌렀다. 가운데 칸에서 «문서를 올려주세요»를 말한
     * 것이 이 이슈다.
     */
    @Test
    void tellsWhyTheCorpusCannotAnswerAlongsideTheSuggestions() {
        when(ragDocumentRepository.findSearchable()).thenReturn(List.of());
        when(ragDocumentRepository.count()).thenReturn(0L);

        AssistantSuggestionsResponse empty = service.suggestions();
        assertThat(empty.corpusState()).isEqualTo(AssistantCorpusState.EMPTY);
        assertThat(empty.questions()).isEmpty();

        /* 올린 직후다 — 업로드는 언제나 `DRAFT`로 들어오므로 검색 대상이 아직 없다(ADR-0034) */
        when(ragDocumentRepository.count()).thenReturn(1L);

        AssistantSuggestionsResponse noneEffective = service.suggestions();
        assertThat(noneEffective.corpusState()).isEqualTo(AssistantCorpusState.NONE_EFFECTIVE);
        assertThat(noneEffective.questions()).as("`READY`가 아니면 빈 목록이다").isEmpty();

        searchable(regulation());

        AssistantSuggestionsResponse ready = service.suggestions();
        assertThat(ready.corpusState()).isEqualTo(AssistantCorpusState.READY);
        assertThat(ready.questions())
                .hasSize(AssistantSuggestions.MAX)
                .allSatisfy(question -> assertThat(question).isNotBlank());
    }

    /*
     * **답할 수 있는 코퍼스에서는 건수를 세지 않는다** — 전체 건수는 «왜 못 답하는가»를 가를 때만
     * 필요한 값이라 검색 대상이 있으면 묻지 않는다(#449).
     */
    @Test
    void doesNotCountTheCorpusWhenItCanAlreadyAnswer() {
        searchable(regulation());

        assertThat(service.suggestions().corpusState()).isEqualTo(AssistantCorpusState.READY);

        verify(ragDocumentRepository, never()).count();
    }

    // ------------------------------------------------------------------ 스트리밍 (#447)

    /*
     * **흘려보낸 조각을 이어 붙이면 한 번에 받은 답과 글자 하나까지 같다.**
     *
     * 두 경로가 프롬프트·검색·인용 해석을 함께 쓴다는 사실이 여기서 드러난다 — 갈리면
     * «스트리밍에서만 틀린 답»이 생기는데 골든셋은 한쪽만 본다.
     */
    @Test
    void streamsTheSameAnswerItWouldHaveGivenAtOnce() {
        searchable(regulation());
        when(ragChunkStore.search(any())).thenReturn(List.of(articleChunk(7, 0.8)));
        chatModel.answer = "정회원 승격은 총회의 동의가 필요합니다. [1] 지어낸 것은 [9] 입니다.";

        AssistantQueryResponse atOnce = service.query(ask("정회원 승격 조건은?"), member);

        RecordingSink sink = new RecordingSink();
        service.queryStreaming(ask("정회원 승격 조건은?"), member, sink);
        sink.await();

        assertThat(sink.deltas).as("한 조각으로 몰아 보내지 않는다").hasSizeGreaterThan(1);
        assertThat(sink.text()).isEqualTo(atOnce.answer());
        assertThat(sink.done.answer()).isEqualTo(atOnce.answer());
        assertThat(sink.done.answered()).isTrue();
        assertThat(sink.done.citations()).isEqualTo(atOnce.citations());
        assertThat(sink.done.applyStatus()).isEqualTo(atOnce.applyStatus());
        assertThat(sink.done.effectiveDate()).isEqualTo(atOnce.effectiveDate());
        assertThat(sink.text()).as("범위 밖 번호는 흘러나가기 전에 지워진다").doesNotContain("[9]");
    }

    /*
     * **거절은 흘려보내지 않는다** — 근거가 없으면 모델을 부르지 않으므로 보낼 조각이 애초에
     * 없고, `done` 하나가 정해진 안내 문구를 싣는다(§6.3). 화면이 할 일이 종전과 같다.
     */
    @Test
    void refusesWithASingleDoneEventInsteadOfStreaming() {
        when(ragDocumentRepository.findSearchable()).thenReturn(List.of());

        RecordingSink sink = new RecordingSink();
        service.queryStreaming(ask("정회원 승격 조건은?"), member, sink);
        sink.await();

        assertThat(sink.deltas).isEmpty();
        assertThat(sink.done.answered()).isFalse();
        assertThat(sink.done.answer()).isEqualTo(AssistantPrompt.NO_EVIDENCE);
        assertThat(sink.done.conversationId()).startsWith("7:");
        assertThat(chatModel.calls).isZero();
    }

    /*
     * ⚠️ **거절의 계단은 스트림이 열리기 전에 끝난다** — 그래서 예외로 나가고 상태 코드가 된다.
     *
     * 이것이 흐트러지면 «화면에 글자가 나오다가 사실은 한도 초과였다»가 성립한다. 413·404·503·
     * 403·429가 모두 같은 자리이며 여기서는 그중 하나로 순서를 못 박는다.
     */
    @Test
    void throwsTheRejectionLadderBeforeAnythingIsStreamed() {
        RecordingSink sink = new RecordingSink();

        assertThatThrownBy(() -> service.queryStreaming(ask("가".repeat(1001)), member, sink))
                .isInstanceOf(GeneralException.class)
                .hasMessageContaining("1000자");

        assertThat(sink.deltas).isEmpty();
        assertThat(sink.done).isNull();
        assertThat(sink.failed).as("첫 바이트 전이라 오류 이벤트가 아니라 상태 코드다").isNull();
    }

    /*
     * **흘려보내기 시작한 뒤의 실패는 예외가 아니라 오류 이벤트다** — 상태 코드를 바꿀 수 없는
     * 자리라서다. 원문은 싣지 않는다(§11 — 모델 SDK의 예외 문장에 질문이 섞여 나온다).
     */
    @Test
    void reportsAFailureAfterTheStreamOpenedAsAnErrorEvent() {
        searchable(regulation());
        when(ragChunkStore.search(any())).thenReturn(List.of(articleChunk(7, 0.8)));
        chatModel.failure = new IllegalStateException("429 quota exceeded for 정회원 승격 조건은?");

        RecordingSink sink = new RecordingSink();
        service.queryStreaming(ask("정회원 승격 조건은?"), member, sink);
        sink.await();

        assertThat(sink.failed).isEqualTo(AssistantErrorCode.ASSISTANT_UPSTREAM_FAILED);
        assertThat(sink.done).isNull();
    }

    /*
     * ⚠️ **흘려보낸 답은 회수하지 않는다** (#447).
     *
     * 모델이 출처를 하나도 달지 않으면 한 번에 받는 경로는 답을 통째로 버리는데(3차 방어선),
     * 스트리밍에서는 그 글자가 이미 읽혔다 — 다른 문장으로 갈아치우는 것이 기각된 «사후 철회»다.
     * 그래서 `answered: false`인데 `answer`가 흘려보낸 문장 그대로이고, 화면은 그 말풍선에
     * «근거 없음»을 표시한다. **대화에는 담지 않는다** — 우리가 뒤에 서지 않는 문장이다.
     */
    @Test
    void marksAnAnswerItAlreadyStreamedAsUngroundedInsteadOfTakingItBack() {
        searchable(regulation());
        when(ragChunkStore.search(any())).thenReturn(List.of(articleChunk(7, 0.8)));
        chatModel.answer = "발췌를 하나도 가리키지 않는 답입니다.";

        RecordingSink sink = new RecordingSink();
        service.queryStreaming(ask("정회원 승격 조건은?"), member, sink);
        sink.await();

        assertThat(sink.text()).isEqualTo("발췌를 하나도 가리키지 않는 답입니다.");
        assertThat(sink.done.answered()).isFalse();
        assertThat(sink.done.answer())
                .as("정해진 안내 문구가 아니라 이미 나간 문장 그대로다")
                .isEqualTo("발췌를 하나도 가리키지 않는 답입니다.");
        assertThat(sink.done.citations()).isEmpty();
        assertThat(sink.done.applyStatus()).isNull();
        assertThat(conversations.history(sink.done.conversationId()))
                .as("근거 없이 나간 답은 다음 턴의 본보기가 되지 않는다")
                .isEmpty();
    }

    /*
     * **받는 쪽이 사라지면 생성을 멈춘다** — 아무도 읽지 않는 답에 무료 쿼터를 쓰지 않는다(§11).
     *
     * 그리고 그때는 오류 이벤트도 보내지 않는다: 알릴 상대가 이미 없고, 공급자 장애로 세면 로그가
     * «탭을 닫은 횟수»만큼 ERROR 로 채워진다.
     */
    @Test
    void stopsGeneratingWhenTheReceiverIsGone() throws Exception {
        searchable(regulation());
        when(ragChunkStore.search(any())).thenReturn(List.of(articleChunk(7, 0.8)));
        chatModel.answer = "아주 긴 답변입니다. 그리고 계속 이어집니다. [1]";

        CountDownLatch closed = new CountDownLatch(1);
        List<String> delivered = new ArrayList<>();
        RecordingSink sink =
                new RecordingSink() {
                    @Override
                    public void delta(String text) {
                        delivered.add(text);
                        closed.countDown();
                        throw new AssistantStreamClosedException();
                    }
                };

        service.queryStreaming(ask("정회원 승격 조건은?"), member, sink);

        assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(50);
        assertThat(delivered).as("첫 조각에서 구독이 끊긴다").hasSize(1);
        assertThat(sink.done).isNull();
        assertThat(sink.failed).as("알릴 상대가 없으므로 오류 이벤트도 없다").isNull();
    }

    // ------------------------------------------------------------------ 이정표 (#453)

    /*
     * **어디서 오래 걸렸는지가 로그 한 줄에 실린다 — 이 이슈의 계기판이다** (#453).
     *
     * 재려는 것은 «첫 글자까지 6초»의 정체이고, 답은 이정표의 차다: A(검색) · B(`첫수신 - 검색`) ·
     * 검증기가 붙든 시간(`첫송신 - 첫수신`). 셋이 **같은 시작점**을 쓰므로 단조 증가여야 하며,
     * 그것이 깨지면 수치가 아니라 **계측이 틀린 것**이다 — 그래서 값이 아니라 순서를 못 박는다
     * (스텁이라 실제 ms 는 전부 0 근처다).
     *
     * **일괄 경로에는 조각이 없어 `-1`이다.** 0으로 두면 «곧바로 일어났다»와 구별되지 않는데,
     * 0ms 는 실제로 나올 수 있는 값이다(임베딩 캐시가 맞은 검색).
     */
    @Test
    void logsWhereTheTimeWentOnOneLine() {
        searchable(regulation());
        when(ragChunkStore.search(any())).thenReturn(List.of(articleChunk(7, 0.8)));
        chatModel.answer = "정회원 승격은 총회의 동의가 필요합니다. [1]";

        try (LogCapture logs = LogCapture.of(AssistantServiceImpl.class)) {
            RecordingSink sink = new RecordingSink();
            service.queryStreaming(ask("정회원 승격 조건은?"), member, sink);
            sink.await();
            service.query(ask("정회원 승격 조건은?"), member);

            List<String> answered =
                    logs.infoMessages().stream()
                            .filter(line -> line.startsWith("규정 도우미 답변"))
                            .toList();
            assertThat(answered).hasSize(2);

            String streamed = answered.get(0);
            assertThat(milestone(streamed, "검색")).as("A — 응답이 열리기까지").isNotNegative();
            assertThat(milestone(streamed, "첫수신"))
                    .as("B 의 끝은 검색보다 앞설 수 없다")
                    .isGreaterThanOrEqualTo(milestone(streamed, "검색"));
            assertThat(milestone(streamed, "첫송신"))
                    .as("검증기가 붙들 수는 있어도 먼저 나갈 수는 없다")
                    .isGreaterThanOrEqualTo(milestone(streamed, "첫수신"));
            assertThat(milestone(streamed, "소요"))
                    .isGreaterThanOrEqualTo(milestone(streamed, "첫송신"));

            String atOnce = answered.get(1);
            assertThat(milestone(atOnce, "검색")).as("A 는 두 경로가 함께 쓴다").isNotNegative();
            assertThat(milestone(atOnce, "첫수신")).as("일괄에는 조각이 없다").isEqualTo(-1);
            assertThat(milestone(atOnce, "첫송신")).isEqualTo(-1);

            assertThat(answered)
                    .as("질문도 답변도 로그에 싣지 않는다 (ADR-0024 · §11)")
                    .noneMatch(line -> line.contains("정회원 승격") || line.contains("총회의 동의"));
        }
    }

    /*
     * 거절도 같은 축으로 남는다 — **근거를 찾지 못한 질의야말로 A 가 전부인 요청**이라
     * 여기 수치가 A 를 가장 깨끗하게 보여 준다(모델을 부르지 않으므로 B 가 없다).
     */
    @Test
    void logsTheSameMilestonesWhenItRefuses() {
        when(ragDocumentRepository.findSearchable()).thenReturn(List.of());

        try (LogCapture logs = LogCapture.of(AssistantServiceImpl.class)) {
            RecordingSink sink = new RecordingSink();
            service.queryStreaming(ask("정회원 승격 조건은?"), member, sink);
            sink.await();

            String refused =
                    logs.infoMessages().stream()
                            .filter(line -> line.startsWith("규정 도우미 거절"))
                            .findFirst()
                            .orElseThrow();

            assertThat(milestone(refused, "검색")).as("검색에 닿기 전에 끊겼다").isEqualTo(-1);
            assertThat(milestone(refused, "소요")).isNotNegative();
        }
    }

    /** `검색=123ms` 에서 123 — 로그를 읽는 사람이 하는 일과 같다 */
    private static long milestone(String line, String name) {
        Matcher found = Pattern.compile(name + "=(-?\\d+)ms").matcher(line);
        assertThat(found.find()).as("%s 이정표가 로그에 없다 — %s", name, line).isTrue();
        return Long.parseLong(found.group(1));
    }

    // ------------------------------------------------------------------ 픽스처

    private AssistantServiceImpl service(boolean enabled) {
        return service(enabled, limiter(5));
    }

    private AssistantServiceImpl service(boolean enabled, AssistantRateLimiter rateLimiter) {
        return new AssistantServiceImpl(
                new AssistantFeature(enabled),
                policy,
                rateLimiter,
                new AssistantSuggestions(),
                new CitationVerifier(policy),
                conversations,
                ragDocumentRepository,
                provider(ragChunkStore),
                provider(ChatClient.builder(chatModel).build()));
    }

    /*
     * 분 한도만 좁히고 나머지는 넉넉히. **창이 넘어가는 규칙은 여기서 보지 않는다** — 시계를
     * 옮겨 가며 보는 것은 `AssistantRateLimiterTest`의 몫이고, 여기서 확인하는 것은 «서비스가
     * 한도를 어느 자리에서 보는가»다.
     */
    private AssistantRateLimiter limiter(int perMinute) {
        return new AssistantRateLimiter(
                perMinute, 1000, 1000, Clock.fixed(Instant.parse("2026-09-15T01:00:00Z"), UTC));
    }

    private MemberEntity member(long id) {
        MemberEntity mock = mock(MemberEntity.class);
        when(mock.getId()).thenReturn(id);
        return mock;
    }

    private AssistantQueryRequest ask(String question) {
        return new AssistantQueryRequest(question, null);
    }

    /** 이어 묻기 — 앞선 응답이 준 식별자를 그대로 싣는다(화면이 하는 일 그대로) */
    private AssistantQueryRequest ask(String question, String conversationId) {
        return new AssistantQueryRequest(question, conversationId);
    }

    private void searchable(RagDocumentEntity... documents) {
        when(ragDocumentRepository.findSearchable()).thenReturn(List.of(documents));
    }

    private RagDocumentEntity regulation() {
        return document(
                1L,
                "REGULATION",
                "SSCC 동아리 회칙",
                (short) 17,
                RagDocumentType.STRUCTURED,
                LocalDate.of(2026, 3, 24));
    }

    private RagDocumentEntity guideline() {
        return document(
                2L,
                "GUIDELINE",
                "2026 지원금 집행 지침",
                (short) 1,
                RagDocumentType.GENERIC,
                LocalDate.of(2026, 3, 1));
    }

    /*
     * 엔티티를 목으로 만드는 것은 확인하려는 것이 «어느 값이 인용에 실리는가»이지 영속화가
     * 아니기 때문이다 — 실제 행으로 보는 것은 컨트롤러 테스트의 몫이다.
     */
    private RagDocumentEntity document(
            Long id,
            String code,
            String name,
            short version,
            RagDocumentType type,
            LocalDate effectiveFrom) {

        RagDocumentEntity document = mock(RagDocumentEntity.class);
        when(document.getId()).thenReturn(id);
        when(document.getName()).thenReturn(name);
        when(document.getType()).thenReturn(type);
        when(document.getApplyStatus()).thenReturn(RagApplyStatus.EFFECTIVE);
        when(document.getEffectiveFrom()).thenReturn(effectiveFrom);
        return document;
    }

    private Document articleChunk(int number, double score) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(RagChunkStore.RAG_DOCUMENT_ID_KEY, 1L);
        metadata.put(RagChunkMetadata.DOC_TYPE, RagDocumentType.STRUCTURED.name());
        metadata.put(RagChunkMetadata.CHAPTER, "제2장 회원");
        metadata.put(RagChunkMetadata.SUPPLEMENTARY, false);
        metadata.put(RagChunkMetadata.ARTICLE_NUMBER, number);
        metadata.put(RagChunkMetadata.ARTICLE_LABEL, "제%d조".formatted(number));
        metadata.put(RagChunkMetadata.CITATION, "제%d조 (회원의 구분)".formatted(number));
        return chunk(metadata, "제2장 회원 · 제%d조 (회원의 구분)\n6항 정회원은 …".formatted(number), score);
    }

    private Document pageChunk(int page, double score) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(RagChunkStore.RAG_DOCUMENT_ID_KEY, 2L);
        metadata.put(RagChunkMetadata.DOC_TYPE, RagDocumentType.GENERIC.name());
        metadata.put(RagChunkMetadata.PAGE, page);
        return chunk(metadata, "2026 지원금 집행 지침 · p.%d\n정산 기한은 …".formatted(page), score);
    }

    private Document chunk(Map<String, Object> metadata, String text, double score) {
        return Document.builder().text(text).metadata(metadata).score(score).build();
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }

    /*
     * 모델 자리의 스텁 — **부른 횟수와 넘긴 프롬프트가 이 테스트의 주된 관측값이다.**
     * «부르지 않았다»가 1차 방어선의 정의라 세는 것 자체가 검증이다.
     */
    private static final class RecordingChatModel implements ChatModel {

        private String answer = "[1]";
        private RuntimeException failure;
        private int calls;
        private Prompt prompt;

        @Override
        public ChatResponse call(Prompt prompt) {
            this.prompt = prompt;
            this.calls++;
            if (failure != null) {
                throw failure;
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
        }

        /*
         * 흘려보내는 쪽 (#447) — **한 글자씩 낸다.** 조각을 크게 주면 «토큰이 조각 경계에 걸쳐
         * 온다»는 스트리밍의 실제 조건을 한 번도 밟지 않는데, 인용 판정이 버텨야 하는 것이
         * 바로 그 경계다.
         */
        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            this.prompt = prompt;
            this.calls++;
            if (failure != null) {
                return Flux.error(failure);
            }
            return Flux.fromStream(answer.chars().mapToObj(Character::toString))
                    .map(
                            piece ->
                                    new ChatResponse(
                                            List.of(new Generation(new AssistantMessage(piece)))));
        }
    }

    /** 흘러나온 것을 그대로 받아 두는 수신자 — 컨트롤러가 SSE 로 하는 일의 알맹이만 */
    private static class RecordingSink implements AssistantAnswerSink {

        private final List<String> deltas = new ArrayList<>();
        private final CountDownLatch finished = new CountDownLatch(1);
        private AssistantQueryResponse done;
        private ErrorCode failed;

        @Override
        public void delta(String text) {
            deltas.add(text);
        }

        @Override
        public void done(AssistantQueryResponse response) {
            this.done = response;
            finished.countDown();
        }

        @Override
        public void failed(ErrorCode errorCode) {
            this.failed = errorCode;
            finished.countDown();
        }

        /** 구독이 다른 스레드에서 도므로 기다린다 — 스텁이라 실제로는 곧바로 끝난다 */
        RecordingSink await() {
            try {
                assertThat(finished.await(5, TimeUnit.SECONDS)).as("스트림이 끝나지 않았다").isTrue();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return this;
        }

        String text() {
            return String.join("", deltas);
        }
    }
}
