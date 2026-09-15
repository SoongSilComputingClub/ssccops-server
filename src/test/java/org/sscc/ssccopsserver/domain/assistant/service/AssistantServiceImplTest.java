package org.sscc.ssccopsserver.domain.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.sscc.ssccopsserver.domain.assistant.code.RagApplyStatus;
import org.sscc.ssccopsserver.domain.assistant.code.RagDocumentType;
import org.sscc.ssccopsserver.domain.assistant.dto.AssistantQueryRequest;
import org.sscc.ssccopsserver.domain.assistant.dto.AssistantQueryResponse;
import org.sscc.ssccopsserver.domain.assistant.entity.RagDocumentEntity;
import org.sscc.ssccopsserver.domain.assistant.repository.RagDocumentRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

/*
 * 질의 한 건의 규칙 — **거절 · 검색 필터 둘 · 인용 검증** (#403 · 기획안 §6 · §14.2).
 *
 * ══ 왜 컨텍스트 없이 보는가 ═════════════════════════════════════
 *
 * 확인하려는 것이 배선이 아니라 **순서와 거절**이다. 모델을 부르는 자리를 스텁으로 두면
 * «부르지 않았다»를 그대로 셀 수 있고(1차 방어선이 그 사실이다), 검색 요청을 잡아 필터를 열어
 * 볼 수 있다 — 스프링을 띄우면 그 둘 다 흐려진다. 실제 모델 품질은 골든셋(#405)의 몫이며
 * CI에 넣지 않는다(외부 의존이고 비결정적이다).
 */
class AssistantServiceImplTest {

    private final RagDocumentRepository ragDocumentRepository = mock(RagDocumentRepository.class);
    private final RagChunkStore ragChunkStore = mock(RagChunkStore.class);
    private final RecordingChatModel chatModel = new RecordingChatModel();
    private final MemberEntity member = mock(MemberEntity.class);

    private final AssistantQueryPolicy policy =
            new AssistantQueryPolicy(8, 0.5, null, null, 1000, 200);

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
                        new AssistantSuggestions(),
                        new CitationVerifier(policy),
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
        chatModel.answer = "정회원은 총회의 동의가 필요합니다. [제7조]";

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
        chatModel.answer = "정회원 승격은 총회의 동의가 필요합니다. [제7조 6항]";

        AssistantQueryResponse response = service.query(ask("정회원 승격 조건은?"), member);

        assertThat(response.answered()).isTrue();
        assertThat(response.answer()).isEqualTo("정회원 승격은 총회의 동의가 필요합니다. [제7조 6항]");
        assertThat(response.citations()).hasSize(1);
        assertThat(response.citations().get(0).article()).isEqualTo("제7조 (회원의 구분)");
        assertThat(response.citations().get(0).clause()).isEqualTo("6항");
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
        chatModel.answer = "제99조에 따라 자동으로 승격됩니다. [제99조]";

        AssistantQueryResponse response = service.query(ask("정회원 승격 조건은?"), member);

        assertThat(response.answered()).isFalse();
        assertThat(response.answer()).isEqualTo(AssistantPrompt.NO_EVIDENCE);
        assertThat(response.citations()).isEmpty();
        assertThat(chatModel.calls).as("모델은 불렀지만 그 답을 쓰지 않았다").isEqualTo(1);
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
        chatModel.answer = "[제7조]";

        service.query(ask("정회원 승격 조건은?"), member);

        String system = chatModel.prompt.getInstructions().get(0).getText();
        String user = chatModel.prompt.getInstructions().get(1).getText();

        assertThat(system).contains("문서 발췌").contains("읽기 전용").contains("3~5문장");
        assertThat(user)
                .contains("[문서 발췌]")
                .contains("인용 표기: [제7조]")
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
                        new AssistantSuggestions(),
                        new CitationVerifier(policy),
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

    // ------------------------------------------------------------------ 추천 질문

    /*
     * 추천 질문은 **지금 검색 대상인 문서에만 매인다**(§13.3) — 코퍼스가 비면 빈 목록이고 그것이
     * 새 환경의 정상 상태다.
     */
    @Test
    void suggestsOnlyWhatTheCorpusCanAnswer() {
        when(ragDocumentRepository.findSearchable()).thenReturn(List.of());
        assertThat(service.suggestions().questions()).isEmpty();

        searchable(regulation());
        assertThat(service.suggestions().questions())
                .hasSize(AssistantSuggestions.MAX)
                .allSatisfy(question -> assertThat(question).isNotBlank());
    }

    // ------------------------------------------------------------------ 픽스처

    private AssistantServiceImpl service(boolean enabled) {
        return new AssistantServiceImpl(
                new AssistantFeature(enabled),
                policy,
                new AssistantSuggestions(),
                new CitationVerifier(policy),
                ragDocumentRepository,
                provider(ragChunkStore),
                provider(ChatClient.builder(chatModel).build()));
    }

    private AssistantQueryRequest ask(String question) {
        return new AssistantQueryRequest(question);
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
        when(document.getDocumentCode()).thenReturn(code);
        when(document.getName()).thenReturn(name);
        when(document.getVersion()).thenReturn(version);
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

        private String answer = "[제7조]";
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
    }
}
