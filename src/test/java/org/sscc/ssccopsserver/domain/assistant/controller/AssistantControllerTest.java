package org.sscc.ssccopsserver.domain.assistant.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
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

import com.jayway.jsonpath.JsonPath;

/*
 * 규정 도우미 질의 API (#403 · #406 · #447 · 상위 ssccops#327).
 *
 * 확인의 중심은 **인가의 계단과 응답의 모양**이다 — 질의는 코퍼스와 달리 **인증만** 요구하고,
 * 거절은 오류가 아니라 `answered: false`인 200이다. 검색·거절의 규칙 자체는 컨텍스트 없이
 * `AssistantServiceImplTest`가 보고, 여기서는 그것이 HTTP 계약으로 나가는지를 본다.
 *
 * **SSE 경로(#447)도 여기서 본다** — 이벤트 이름 셋 · `ApiResponse` 봉투가 없다는 사실 ·
 * **첫 바이트 전의 거절이 종전 그대로 상태 코드라는 것**. 그 셋이 화면과 나눠 갖는 계약이다.
 *
 * **기능 플래그를 `properties`로 켠다** — `application-test.yaml`에 켜 두지 않은 것은 «test
 * 프로필도 켜지 않는다»가 `AssistantWiringTest`가 지키는 사실이기 때문이고, 그 대가로 이
 * 클래스가 컨텍스트를 하나 갖는다(`RagDocumentControllerTest`와 같은 자리 · #103).
 *
 * **채팅 스텁을 저장소 스텁과 나눠 import 한다** — 한 벌로 묶으면 «키가 없는 서버에는
 * ChatClient 빈이 없다»를 지키는 `AssistantWiringTest`가 깨진다.
 *
 * ⚠️ **`print = NONE`은 취향이 아니라 SSE 때문이다** (#447). 스프링 부트가 기본으로 다는
 * 결과 출력 핸들러(`LinesWritingResultHandler`)가 `perform()` 끝에서 **응답 헤더를 훑는데**,
 * 그때 스트림을 쓰는 스레드가 같은 `MockHttpServletResponse`를 건드리고 있어 드물게
 * `ConcurrentModificationException`으로 터진다 — 실제로 한 번 겪었고, 실패가 무작위로 옮겨
 * 다녀 원인을 찾기 어려운 종류다. 실제 컨테이너에는 없는 경합이라(그쪽 응답은 목이 아니다)
 * 고칠 곳은 본 코드가 아니라 여기다. 잃는 것은 실패했을 때의 요청·응답 덤프뿐이다.
 */
@SpringBootTest(properties = "ssccops.assistant.enabled=true")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
@Import({TestJwtDecoderConfig.class, AssistantStubConfig.class, AssistantChatStubConfig.class})
@Transactional
class AssistantControllerTest {

    private static final String QUERIES = "/v1/assistant/queries";
    private static final String STREAM = "/v1/assistant/queries/stream";
    private static final String SUGGESTIONS = "/v1/assistant/suggestions";
    private static final String CONVERSATIONS = "/v1/assistant/conversations/";

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
        chatModel.answerWith("정회원 승격은 총회의 동의가 필요합니다. [1]");

        query("정회원 승격 조건은?")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answered").value(true))
                .andExpect(jsonPath("$.data.answer").value(containsString("총회의 동의")))
                .andExpect(jsonPath("$.data.citations", hasSize(1)))
                // 본문의 [1]과 짝이고, 표기는 서버가 붙인다 (#447)
                .andExpect(jsonPath("$.data.citations[0].ref").value(1))
                .andExpect(jsonPath("$.data.citations[0].marker").value("제7조"))
                .andExpect(jsonPath("$.data.citations[0].citationType").value("ARTICLE"))
                .andExpect(jsonPath("$.data.citations[0].docTitle").value("SSCC 동아리 회칙"))
                .andExpect(jsonPath("$.data.citations[0].chapter").value("제2장 회원"))
                .andExpect(jsonPath("$.data.citations[0].supplementary").value(false))
                .andExpect(jsonPath("$.data.citations[0].article").value("제7조 (회원의 구분)"))
                // 번호 참조에는 항 정보가 없다 — 지어내지 않는다 (#447)
                .andExpect(jsonPath("$.data.citations[0].clause").value(nullValue()))
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
        chatModel.answerWith("제99조에 따라 자동으로 승격됩니다. [9]");

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

    // ------------------------------------------------------------------ 대화 (#406)

    /*
     * **식별자는 서버가 발급하고 응답에 실려 온다** (§7.4) — 화면은 그것을 들고 다니기만 한다.
     *
     * 클라이언트가 만들게 두면 남의 식별자를 넣어 **남의 대화를 읽을 수 있다.** 힙에 둔다고
     * 이 규칙이 느슨해지지 않는다.
     */
    @Test
    void handsOutAConversationIdTheWebCanKeepAskingWith() throws Exception {
        indexedAndEffectiveRegulation();
        ragChunkStore.add(java.util.List.of(articleChunk()));
        chatModel.answerWith("정회원 승격은 총회의 동의가 필요합니다. [1]");

        query("정회원 승격 조건은?")
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data.conversationId").value(startsWith(member.getId() + ":")));
    }

    /* **거절에도 실린다** — 근거를 못 찾은 첫 질문 뒤에 다시 묻는 것이 흔한 사용이다 */
    @Test
    void handsOutAConversationIdEvenWhenItRefuses() throws Exception {
        query("정회원 승격 조건은?")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answered").value(false))
                .andExpect(
                        jsonPath("$.data.conversationId").value(startsWith(member.getId() + ":")));
    }

    /* 같은 식별자로 다시 물으면 **앞선 턴이 맥락으로 들어간다** — 시스템 → 이력 → 이번 발췌·질문 */
    @Test
    void continuesTheConversationOnTheNextQuestion() throws Exception {
        String conversationId = answeredConversation("정회원 승격 조건은?");

        query("그럼 준회원은요?", conversationId).andExpect(status().isOk());

        assertThat(chatModel.lastPrompt().getInstructions())
                .as("시스템 · 앞선 질문 · 앞선 답변 · 이번 질문")
                .hasSize(4);
    }

    /* **남의 대화는 이어 갈 수 없다** — 403이며 화면은 들고 있던 값을 버리고 새 대화로 다시 보낸다 */
    @Test
    void refusesToContinueSomeoneElsesConversation() throws Exception {
        query("정회원 승격 조건은?", (member.getId() + 1) + ":" + UUID.randomUUID())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ASSISTANT_CONVERSATION_FORBIDDEN"));
    }

    /* 서버가 발급하지 않은 모양도 **같은 거절**이다 — 통과하는 것은 발급한 값뿐이다 */
    @Test
    void refusesAConversationIdTheServerDidNotIssue() throws Exception {
        query("정회원 승격 조건은?", member.getId() + ":not-a-uuid")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ASSISTANT_CONVERSATION_FORBIDDEN"));
    }

    /* `↺` — 지우면 다음 질문은 맥락 없이 처음부터 답한다(§13.1) */
    @Test
    void clearsTheConversationOnDelete() throws Exception {
        String conversationId = answeredConversation("정회원 승격 조건은?");

        mockMvc.perform(authorized(delete(CONVERSATIONS + conversationId)))
                .andExpect(status().isOk());

        query("그럼 준회원은요?", conversationId).andExpect(status().isOk());

        assertThat(chatModel.lastPrompt().getInstructions()).as("지운 뒤에는 시스템과 이번 질문뿐이다").hasSize(2);
    }

    /*
     * **없는 대화를 지우는 것도 200이다.** 24시간 슬라이딩 만료가 지난 대화와 아직 한 번도 묻지
     * 않은 식별자를 가를 값이 서버에 없고, 화면이 할 일이 «처음 화면으로 되돌린다»로 같다.
     */
    @Test
    void deletingAConversationThatIsNotThereStillSucceeds() throws Exception {
        mockMvc.perform(
                        authorized(
                                delete(CONVERSATIONS + member.getId() + ":" + UUID.randomUUID())))
                .andExpect(status().isOk());
    }

    /* **지우는 것도 남의 대화에 닿는 일이다** — 읽기와 같은 규칙을 쓴다 */
    @Test
    void refusesToClearSomeoneElsesConversation() throws Exception {
        mockMvc.perform(
                        authorized(
                                delete(
                                        CONVERSATIONS
                                                + (member.getId() + 1)
                                                + ":"
                                                + UUID.randomUUID())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ASSISTANT_CONVERSATION_FORBIDDEN"));
    }

    /* 초기화도 인증을 요구한다 — `/public/v1/**` 아래에 두지 않았다 */
    @Test
    void doesNotLetAnonymousCallersClearAnything() throws Exception {
        mockMvc.perform(delete(CONVERSATIONS + "1:" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ SSE 스트리밍 (#447)

    /*
     * **본문이 조각으로 나가고 마지막에 인용·판본이 온다.**
     *
     * 이벤트 이름 셋(`delta`·`done`·`error`)이 화면과 나눠 갖는 계약이고, 조각을 이어 붙이면
     * `done`의 `answer`와 글자 하나까지 같다 — 그래야 화면이 «받은 글자»와 «확정된 답»을 두 벌로
     * 들고 있지 않아도 된다.
     */
    @Test
    void streamsTheAnswerInPiecesAndEndsWithTheCitations() throws Exception {
        indexedAndEffectiveRegulation();
        ragChunkStore.add(List.of(articleChunk()));
        chatModel.answerWith("정회원 승격은 총회의 동의가 필요합니다. [1]");

        MvcResult result = stream("정회원 승격 조건은?");
        String body = awaitStream(result);

        assertThat(result.getResponse().getContentType())
                .startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(events(body, "delta")).as("한 조각으로 몰아 보내지 않는다").hasSizeGreaterThan(1);

        String streamed =
                events(body, "delta").stream()
                        .map(event -> (String) JsonPath.read(event, "$.text"))
                        .reduce("", String::concat);
        assertThat(streamed).isEqualTo("정회원 승격은 총회의 동의가 필요합니다. [1]");

        String done = onlyEvent(body, "done");
        assertThat((Boolean) JsonPath.read(done, "$.answered")).isTrue();
        assertThat((String) JsonPath.read(done, "$.answer")).isEqualTo(streamed);
        assertThat((Integer) JsonPath.read(done, "$.citations[0].ref")).isEqualTo(1);
        assertThat((String) JsonPath.read(done, "$.citations[0].marker")).isEqualTo("제7조");
        assertThat((String) JsonPath.read(done, "$.applyStatus")).isEqualTo("EFFECTIVE");
        assertThat((String) JsonPath.read(done, "$.conversationId"))
                .startsWith(member.getId() + ":");
    }

    /*
     * ⚠️ **SSE 이벤트에는 `ApiResponse` 봉투가 없다** — 전역 규약의 예외이고, 화면이 그것을 알고
     * 있어야 하므로 여기서 못 박는다. 한 응답에 이벤트가 여러 번 나가는데 `success`·`code`·
     * `message`를 조각마다 되풀이하면 아무것도 말하지 않는다.
     */
    @Test
    void doesNotWrapStreamEventsInTheApiResponseEnvelope() throws Exception {
        indexedAndEffectiveRegulation();
        ragChunkStore.add(List.of(articleChunk()));

        String body = awaitStream(stream("정회원 승격 조건은?"));

        assertThat(body).doesNotContain("\"success\"").doesNotContain("\"data\"");
    }

    /*
     * **거절은 조각 없이 `done` 하나다** — 근거가 없으면 모델을 부르지 않으므로 보낼 글자가 없다.
     * 화면이 할 일은 한 번에 받는 경로와 같다(정해진 안내 문구를 말풍선으로).
     */
    @Test
    void refusesWithASingleDoneEventWhenThereIsNoEvidence() throws Exception {
        String body = awaitStream(stream("정회원 승격 조건은?"));

        assertThat(events(body, "delta")).isEmpty();

        String done = onlyEvent(body, "done");
        assertThat((Boolean) JsonPath.read(done, "$.answered")).isFalse();
        assertThat((String) JsonPath.read(done, "$.answer")).contains("찾지 못했습니다");
        assertThat((List<?>) JsonPath.read(done, "$.citations")).isEmpty();
        assertThat(chatModel.calls()).isZero();
    }

    /*
     * ⚠️ **첫 바이트 전의 거절은 종전 그대로 상태 코드다** — SSE 로 바꾸면서 거절의 계단이
     * 흐려지지 않았다는 것이 이 테스트다. 흐려지면 «화면에 글자가 나오다가 사실은 한도
     * 초과였다»가 성립한다.
     */
    @Test
    void keepsTheRejectionLadderOnStatusCodesEvenOnTheStreamPath() throws Exception {
        mockMvc.perform(streamRequest("가".repeat(1001)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("ASSISTANT_QUESTION_TOO_LONG"));

        mockMvc.perform(
                        post(STREAM)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"question\":\"정회원 승격 조건은?\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(
                        authorized(post(STREAM))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"question\":\"정회원 승격 조건은?\",\"conversationId\":\"%d:not-a-uuid\"}"
                                                .formatted(member.getId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ASSISTANT_CONVERSATION_FORBIDDEN"));
    }

    /*
     * **흘려보내기 시작한 뒤의 실패는 오류 이벤트다** — 헤더가 이미 나가 상태 코드를 바꿀 수
     * 없기 때문이며(`GlobalExceptionHandler`가 닿지 못하는 자리다), 그때까지 그려진 글자는
     * 화면에 남는다. 원문 오류는 싣지 않는다(§11).
     */
    @Test
    void reportsAMidStreamFailureAsAnErrorEvent() throws Exception {
        indexedAndEffectiveRegulation();
        ragChunkStore.add(List.of(articleChunk()));
        chatModel.answerWith("정회원 승격은 총회의 동의가 필요합니다. [1]");
        chatModel.failStreamAfterFirstDelta(
                new IllegalStateException("429 quota exceeded for 정회원 승격 조건은?"));

        String body = awaitStream(stream("정회원 승격 조건은?"));

        assertThat(events(body, "delta")).as("끊기기 전까지 그려진 글자는 남는다").isNotEmpty();
        assertThat(events(body, "done")).isEmpty();

        String error = onlyEvent(body, "error");
        assertThat((String) JsonPath.read(error, "$.code")).isEqualTo("ASSISTANT_UPSTREAM_FAILED");
        assertThat((String) JsonPath.read(error, "$.message")).isEqualTo("지금은 답변을 만들 수 없습니다.");
        assertThat(body).as("모델 SDK의 예외 문장에는 질문이 섞여 나온다").doesNotContain("quota exceeded");
    }

    /*
     * ⚠️ **흘려보낸 답은 회수하지 않는다** (#447) — 모델이 출처를 하나도 달지 않으면 `done`이
     * `answered: false`에 **이미 나간 문장 그대로**를 싣는다(정해진 안내 문구가 아니다). 다 읽은
     * 문장을 다른 문장으로 갈아치우는 것이 기각된 «사후 철회»다. 한 번에 받는 경로는 그때 답을
     * 통째로 버린다 — 아직 아무것도 나가지 않았기 때문이다.
     */
    @Test
    void marksAnUngroundedStreamedAnswerInsteadOfTakingItBack() throws Exception {
        indexedAndEffectiveRegulation();
        ragChunkStore.add(List.of(articleChunk()));
        chatModel.answerWith("제99조에 따라 자동으로 승격됩니다. [9]");

        String body = awaitStream(stream("정회원 승격 조건은?"));
        String done = onlyEvent(body, "done");

        assertThat((Boolean) JsonPath.read(done, "$.answered")).isFalse();
        assertThat((String) JsonPath.read(done, "$.answer"))
                .isEqualTo("제99조에 따라 자동으로 승격됩니다.")
                .as("범위 밖 번호는 나가기 전에 지워진다");
        assertThat((List<?>) JsonPath.read(done, "$.citations")).isEmpty();
        assertThat(body).doesNotContain("[9]");
    }

    /* 대화도 같은 규칙이다 — 서버가 발급한 값이 `done`에 실리고 다음 질문에 그대로 쓰인다 */
    @Test
    void carriesTheConversationThroughTheStreamPathToo() throws Exception {
        indexedAndEffectiveRegulation();
        ragChunkStore.add(List.of(articleChunk()));
        chatModel.answerWith("정회원 승격은 총회의 동의가 필요합니다. [1]");

        String conversationId =
                JsonPath.read(
                        onlyEvent(awaitStream(stream("정회원 승격 조건은?")), "done"), "$.conversationId");

        awaitStream(stream("그럼 준회원은요?", conversationId));

        assertThat(chatModel.lastPrompt().getInstructions())
                .as("시스템 · 앞선 질문 · 앞선 답변 · 이번 질문")
                .hasSize(4);
    }

    // ------------------------------------------------------------------ 추천 질문

    /*
     * 추천 질문은 **지금 답할 수 있는 것만**이고, 답할 수 없으면 **«왜»가 함께 나간다**(§13.1 ·
     * §13.3 · #449). 화면이 빈 상태 문구를 그 값으로 가르므로 **셋이 짝을 이루는 것**이 계약이다.
     *
     * 가운데 칸이 이 이슈가 열린 자리다 — 문서가 있는데 «등록된 문서가 없다»고 말했고, 업로드가
     * 언제나 `DRAFT`로 들어오므로(ADR-0034) 그것이 **첫 업로드마다 반드시 지나는 화면**이다.
     */
    @Test
    void servesSuggestionsThatTheCorpusCanActuallyAnswer() throws Exception {
        mockMvc.perform(get(SUGGESTIONS).header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.corpusState").value("EMPTY"))
                .andExpect(jsonPath("$.data.questions", hasSize(0)));

        // 올렸다 — 색인도 시행도 아직이다
        ragDocumentRepository.saveAndFlush(registerRegulation());

        mockMvc.perform(get(SUGGESTIONS).header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.corpusState").value("NONE_EFFECTIVE"))
                .andExpect(jsonPath("$.data.questions", hasSize(0)));

        // 색인이 끝나고 「시행」을 눌렀다
        indexedAndEffectiveRegulation();

        mockMvc.perform(get(SUGGESTIONS).header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.corpusState").value("READY"))
                .andExpect(jsonPath("$.data.questions", hasSize(3)));
    }

    // ------------------------------------------------------------------ 픽스처

    private ResultActions query(String question) throws Exception {
        return mockMvc.perform(
                authorized(post(QUERIES))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"%s\"}".formatted(question)));
    }

    /** 이어 묻기 — 화면이 앞선 응답에서 받은 값을 그대로 싣는다(#406) */
    private ResultActions query(String question, String conversationId) throws Exception {
        return mockMvc.perform(
                authorized(post(QUERIES))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"question\":\"%s\",\"conversationId\":\"%s\"}"
                                        .formatted(question, conversationId)));
    }

    /** 답한 질의 하나 — 인용까지 통과해야 대화에 남는다 */
    private String answeredConversation(String question) throws Exception {
        indexedAndEffectiveRegulation();
        ragChunkStore.add(java.util.List.of(articleChunk()));
        chatModel.answerWith("정회원 승격은 총회의 동의가 필요합니다. [1]");

        return JsonPath.read(
                query(question)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.data.conversationId");
    }

    private MvcResult stream(String question) throws Exception {
        return mockMvc.perform(streamRequest(question)).andExpect(status().isOk()).andReturn();
    }

    private MvcResult stream(String question, String conversationId) throws Exception {
        return mockMvc.perform(
                        authorized(post(STREAM))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"question\":\"%s\",\"conversationId\":\"%s\"}"
                                                .formatted(question, conversationId)))
                .andExpect(status().isOk())
                .andReturn();
    }

    private MockHttpServletRequestBuilder streamRequest(String question) {
        return authorized(post(STREAM))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"%s\"}".formatted(question));
    }

    /*
     * 구독이 다른 스레드에서 돌므로 **마지막 이벤트가 다 실릴 때까지 기다린다.**
     *
     * `isAsyncStarted()`를 보지 않는 것은 `MockAsyncContext`가 `complete()`에서 그 값을 내리지
     * 않기 때문이다(실제 컨테이너와 다른 자리다). 끝의 빈 줄까지 확인하는 것은 `SseEmitter`가
     * 이벤트 하나를 여러 번에 나눠 쓰기 때문이며, 그 사이에 읽으면 JSON이 잘린다.
     *
     * ⚠️ **UTF-8로 읽는다** — `text/event-stream`에는 charset이 붙지 않아
     * `MockHttpServletResponse`의 기본값(ISO-8859-1)으로 읽으면 한글이 깨진다.
     */
    private String awaitStream(MvcResult result) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            boolean terminated = body.contains("event:done") || body.contains("event:error");
            if (terminated && body.endsWith("\n\n")) {
                return body;
            }
            Thread.sleep(10);
        }
        throw new AssertionError(
                "스트림이 끝나지 않았다: " + result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** `event:이름` 뒤에 붙은 `data:` 줄들 — 화면이 읽는 것과 같은 축으로 본다 */
    private static List<String> events(String body, String name) {
        List<String> found = new ArrayList<>();
        String current = null;
        for (String line : body.split("\n")) {
            if (line.startsWith("event:")) {
                current = line.substring("event:".length()).strip();
            } else if (line.startsWith("data:") && name.equals(current)) {
                found.add(line.substring("data:".length()));
            }
        }
        return found;
    }

    private static String onlyEvent(String body, String name) {
        List<String> found = events(body, name);
        assertThat(found).as("%s 이벤트는 한 번뿐이다".formatted(name)).hasSize(1);
        return found.get(0);
    }

    private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + memberToken);
    }

    private RagDocumentEntity registerRegulation() {
        return RagDocumentEntity.register(
                "SSCC 동아리 회칙", RagDocumentType.STRUCTURED, "회칙.md", 1024, member);
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
