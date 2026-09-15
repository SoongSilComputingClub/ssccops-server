package org.sscc.ssccopsserver.domain.assistant.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.assistant.code.RagApplyStatus;
import org.sscc.ssccopsserver.domain.assistant.code.RagDocumentType;
import org.sscc.ssccopsserver.domain.assistant.entity.RagDocumentEntity;
import org.sscc.ssccopsserver.domain.assistant.repository.RagDocumentRepository;
import org.sscc.ssccopsserver.domain.assistant.service.RagChunkMetadata;
import org.sscc.ssccopsserver.domain.assistant.service.RagChunkStore;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.support.AssistantChatStubConfig;
import org.sscc.ssccopsserver.support.AssistantStubConfig;
import org.sscc.ssccopsserver.support.InMemoryRagChunkStore;
import org.sscc.ssccopsserver.support.MemberFixture;
import org.sscc.ssccopsserver.support.StubChatModel;
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

/*
 * 규정 도우미 질의 API (#403 · 상위 ssccops#327).
 *
 * 확인의 중심은 **인가의 계단과 응답의 모양**이다 — 질의는 코퍼스와 달리 **인증만** 요구하고,
 * 거절은 오류가 아니라 `answered: false`인 200이다. 검색·거절의 규칙 자체는 컨텍스트 없이
 * `AssistantServiceImplTest`가 보고, 여기서는 그것이 HTTP 계약으로 나가는지를 본다.
 *
 * **기능 플래그를 `properties`로 켠다** — `application-test.yaml`에 켜 두지 않은 것은 «test
 * 프로필도 켜지 않는다»가 `AssistantWiringTest`가 지키는 사실이기 때문이고, 그 대가로 이
 * 클래스가 컨텍스트를 하나 갖는다(`RagDocumentControllerTest`와 같은 자리 · #103).
 *
 * **채팅 스텁을 저장소 스텁과 나눠 import 한다** — 한 벌로 묶으면 «키가 없는 서버에는
 * ChatClient 빈이 없다»를 지키는 `AssistantWiringTest`가 깨진다.
 */
@SpringBootTest(properties = "ssccops.assistant.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestJwtDecoderConfig.class, AssistantStubConfig.class, AssistantChatStubConfig.class})
@Transactional
class AssistantControllerTest {

    private static final String QUERIES = "/v1/assistant/queries";
    private static final String SUGGESTIONS = "/v1/assistant/suggestions";

    @Autowired private MockMvc mockMvc;
    @Autowired private RagDocumentRepository ragDocumentRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private InMemoryRagChunkStore ragChunkStore;
    @Autowired private StubChatModel chatModel;

    private MemberEntity member;
    private UUID memberToken;

    @BeforeEach
    void setUp() throws Exception {
        // 스텁 둘이 컨텍스트와 함께 살아 클래스 경계를 넘으므로 여기서 비운다
        ragChunkStore.clear();
        chatModel.reset();

        memberToken = UUID.randomUUID();
        member =
                MemberFixture.save(
                        memberRepository,
                        memberGradeRepository,
                        memberStatusRepository,
                        memberToken,
                        "20260403",
                        "질문자",
                        "20260403@sscc.org");
    }

    // ------------------------------------------------------------------ 인가의 계단

    /*
     * **권한이 하나도 없는 회원이 물을 수 있다** — 질의는 인증만 요구한다(§11).
     *
     * 규정은 회원에게 공개된 문서라 잠글 것이 없고, 잠그는 것은 코퍼스를 바꾸는 쪽이다
     * (`RAG_DOCUMENT_MANAGE` · 컨트롤러가 나뉜 이유).
     */
    @Test
    void anyMemberMayAskWithoutAnyAuthority() throws Exception {
        indexedAndEffectiveRegulation();
        ragChunkStore.add(java.util.List.of(articleChunk()));
        chatModel.answerWith("정회원 승격은 총회의 동의가 필요합니다. [제7조 6항]");

        query("정회원 승격 조건은?")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answered").value(true))
                .andExpect(jsonPath("$.data.answer").value(containsString("총회의 동의")))
                .andExpect(jsonPath("$.data.citations", hasSize(1)))
                .andExpect(jsonPath("$.data.citations[0].citationType").value("ARTICLE"))
                .andExpect(jsonPath("$.data.citations[0].docTitle").value("SSCC 동아리 회칙"))
                .andExpect(jsonPath("$.data.citations[0].docVer").value(1))
                .andExpect(jsonPath("$.data.citations[0].chapter").value("제2장 회원"))
                .andExpect(jsonPath("$.data.citations[0].supplementary").value(false))
                .andExpect(jsonPath("$.data.citations[0].article").value("제7조 (회원의 구분)"))
                .andExpect(jsonPath("$.data.citations[0].clause").value("6항"))
                // ARTICLE이면 page는 null이다 — 서버가 대체값을 만들지 않는다(§6.3)
                .andExpect(jsonPath("$.data.citations[0].page").value(nullValue()))
                .andExpect(jsonPath("$.data.applyStatus").value("EFFECTIVE"))
                .andExpect(jsonPath("$.data.effectiveDate").value("2026-03-24"));
    }

    /* 로그인했지만 가입하지 않은 주체는 403 `SIGNUP_REQUIRED`다 — `@CurrentMember`의 계단 그대로 */
    @Test
    void requiresSignupBeforeAsking() throws Exception {
        mockMvc.perform(
                        post(QUERIES)
                                .header("Authorization", "Bearer " + UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"question\":\"정회원 승격 조건은?\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIGNUP_REQUIRED"));
    }

    /* 토큰이 없으면 401이다 — `/public/v1/**` 아래에 두지 않았다는 사실이 여기서 드러난다 */
    @Test
    void isNotOpenToAnonymousCallers() throws Exception {
        mockMvc.perform(
                        post(QUERIES)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"question\":\"정회원 승격 조건은?\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ 거절은 200이다

    /*
     * **코퍼스가 비어 있으면 모델을 부르지 않고 거절한다** — 새 환경의 기본 상태다(§12.5).
     *
     * 오류가 아니라 200인 것은 화면이 그 문구를 말풍선으로 그려야 하기 때문이고, `citations`가
     * **빈 배열(null 아님)**인 것은 화면이 분기 없이 그릴 수 있게 하기 위해서다.
     */
    @Test
    void refusesWithTwoHundredWhenTheCorpusIsEmpty() throws Exception {
        query("정회원 승격 조건은?")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answered").value(false))
                .andExpect(jsonPath("$.data.answer").value(containsString("찾지 못했습니다")))
                .andExpect(jsonPath("$.data.citations", hasSize(0)))
                .andExpect(jsonPath("$.data.applyStatus").value(nullValue()))
                .andExpect(jsonPath("$.data.effectiveDate").value(nullValue()));

        assertThat(chatModel.calls()).as("근거가 없으면 모델을 부르지 않는다 — 1차 방어선").isZero();
    }

    /*
     * **색인이 끝나지 않은 판본은 검색되지 않는다** — 조건 둘 중 하나만 빠져도 새어 나간다.
     *
     * 시행 중으로 올라갈 수 없는 상태이므로(409 `RAG_DOCUMENT_NOT_INDEXED`) 여기서는 `DRAFT`인
     * 채로 두고, 질의가 그것을 보지 않는다는 사실만 본다.
     */
    @Test
    void doesNotSearchAVersionThatIsNotIndexed() throws Exception {
        RagDocumentEntity draft = ragDocumentRepository.save(registerRegulation());
        ragChunkStore.add(java.util.List.of(articleChunk(draft.getId())));

        query("정회원 승격 조건은?").andExpect(jsonPath("$.data.answered").value(false));

        assertThat(chatModel.calls()).isZero();
    }

    /* **의결 전 개정안(`DRAFT`)도 보지 않는다** — 색인은 끝났지만 시행 중이 아니다 */
    @Test
    void doesNotSearchAVersionThatIsNotInForce() throws Exception {
        RagDocumentEntity amendment = ragDocumentRepository.save(registerRegulation());
        amendment.startIndexing(Instant.now());
        amendment.completeIndexing(40, Instant.now());
        ragDocumentRepository.flush();
        ragChunkStore.add(java.util.List.of(articleChunk(amendment.getId())));

        query("정회원 승격 조건은?").andExpect(jsonPath("$.data.answered").value(false));

        assertThat(chatModel.calls()).isZero();
    }

    /*
     * **모델이 지어낸 인용은 답변까지 함께 버린다** — 3차 방어선(§6.1).
     *
     * 출처 없는 규정 답변은 틀린 답보다 나쁘다. 사용자에게 돌아가는 것은 「찾지 못했다」와 같은
     * 문구이며, 모델을 한 번 불렀다는 사실은 로그에만 남는다.
     */
    @Test
    void discardsAnAnswerWhoseCitationsDoNotCheckOut() throws Exception {
        indexedAndEffectiveRegulation();
        ragChunkStore.add(java.util.List.of(articleChunk()));
        chatModel.answerWith("제99조에 따라 자동으로 승격됩니다. [제99조]");

        query("정회원 승격 조건은?")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answered").value(false))
                .andExpect(jsonPath("$.data.citations", hasSize(0)));

        assertThat(chatModel.calls()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 오류

    /* 빈 질문은 요청이 형식을 어긴 것이라 400이다 */
    @Test
    void rejectsAnEmptyQuestion() throws Exception {
        mockMvc.perform(
                        post(QUERIES)
                                .header("Authorization", "Bearer " + memberToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"question\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    /* 1,000자를 넘는 질문은 **413**이다 — 도메인이 정한 용량 규칙이라 `@Valid`의 400과 갈린다(§8.1) */
    @Test
    void rejectsAQuestionOverTheLengthLimit() throws Exception {
        query("가".repeat(1001))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("ASSISTANT_QUESTION_TOO_LONG"));
    }

    /* 모델 호출 실패는 503이고 **원문 오류가 응답에 새어 나가지 않는다**(§11) */
    @Test
    void translatesAModelFailureIntoOurOwnCode() throws Exception {
        indexedAndEffectiveRegulation();
        ragChunkStore.add(java.util.List.of(articleChunk()));
        chatModel.failWith(new IllegalStateException("429 quota exceeded for 정회원 승격 조건은?"));

        query("정회원 승격 조건은?")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ASSISTANT_UPSTREAM_FAILED"))
                .andExpect(jsonPath("$.message").value("지금은 답변을 만들 수 없습니다."));
    }

    /*
     * **한도를 넘으면 429**이며, 공급자가 아니라 **우리가 먼저 끊은 것**이다 (#404 · §11).
     *
     * 무료 쿼터가 API 키 단위의 공유 자원이라 한 사람의 루프가 전원의 답변을 멈춘다. 여섯 번째
     * 질의가 막히는 것은 회원당 분 한도가 5회라서이고, 그 값은 `application-test.yaml`이 손대지
     * 않은 운영 기본값이다 — 여기서 확인하는 것은 **그 한도가 HTTP 계약으로 나가는 모양**이고,
     * 창이 넘어가는 규칙은 시계를 옮길 수 있는 `AssistantRateLimiterTest`가 본다.
     *
     * 코퍼스를 세우지 않은 것은 **거절로 끝난 질의도 한 칸을 쓰기 때문**이다 — 세는 것이 «답한
     * 질의»가 아니라 «받아들인 질의»라는 사실이 여기서도 그대로 드러난다.
     */
    @Test
    void refusesWithTooManyRequestsOnceTheMinuteQuotaIsSpent() throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            query("정회원 승격 조건은?").andExpect(status().isOk());
        }

        query("정회원 승격 조건은?")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ASSISTANT_RATE_LIMITED"))
                .andExpect(jsonPath("$.message").value(containsString("잠시 뒤")));
    }

    // ------------------------------------------------------------------ 추천 질문

    /*
     * 추천 질문은 **지금 답할 수 있는 것만**이다(§13.3) — 코퍼스가 비면 빈 배열이고 화면은 그때
     * 고지 문구만 그린다.
     */
    @Test
    void servesSuggestionsThatTheCorpusCanActuallyAnswer() throws Exception {
        mockMvc.perform(get(SUGGESTIONS).header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.questions", hasSize(0)));

        indexedAndEffectiveRegulation();

        mockMvc.perform(get(SUGGESTIONS).header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.questions", hasSize(3)));
    }

    // ------------------------------------------------------------------ 픽스처

    private ResultActions query(String question) throws Exception {
        return mockMvc.perform(
                authorized(post(QUERIES))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"%s\"}".formatted(question)));
    }

    private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + memberToken);
    }

    private RagDocumentEntity registerRegulation() {
        return RagDocumentEntity.register(
                "REGULATION",
                "SSCC 동아리 회칙",
                RagDocumentType.STRUCTURED,
                RagDocumentEntity.FIRST_VERSION,
                "회칙.md",
                1024,
                member);
    }

    /** 색인이 끝났고 시행 중인 판본 — <b>검색이 보는 유일한 조합이다</b> */
    private RagDocumentEntity indexedAndEffectiveRegulation() {
        RagDocumentEntity document = ragDocumentRepository.save(registerRegulation());
        document.startIndexing(Instant.now());
        document.completeIndexing(40, Instant.now());
        document.changeApplyStatus(RagApplyStatus.EFFECTIVE, LocalDate.of(2026, 3, 24));
        ragDocumentRepository.flush();
        return document;
    }

    private Document articleChunk() {
        return articleChunk(ragDocumentRepository.findAll().get(0).getId());
    }

    private Document articleChunk(Long ragDocId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(RagChunkStore.RAG_DOCUMENT_ID_KEY, ragDocId);
        metadata.put(RagChunkMetadata.DOC_TYPE, RagDocumentType.STRUCTURED.name());
        metadata.put(RagChunkMetadata.APPLY_STATUS, RagApplyStatus.EFFECTIVE.name());
        metadata.put(RagChunkMetadata.CHAPTER, "제2장 회원");
        metadata.put(RagChunkMetadata.SUPPLEMENTARY, false);
        metadata.put(RagChunkMetadata.ARTICLE_NUMBER, 7);
        metadata.put(RagChunkMetadata.ARTICLE_LABEL, "제7조");
        metadata.put(RagChunkMetadata.CITATION, "제7조 (회원의 구분)");
        return Document.builder()
                .text("제2장 회원 · 제7조 (회원의 구분)\n6항 정회원은 총회의 동의를 얻어 승격한다.")
                .metadata(metadata)
                .score(0.9)
                .build();
    }
}
