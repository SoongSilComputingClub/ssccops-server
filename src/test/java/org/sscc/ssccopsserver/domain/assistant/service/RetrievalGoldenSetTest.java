package org.sscc.ssccopsserver.domain.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.sscc.ssccopsserver.support.AssistantGoldenSet.ANSWERABLE;
import static org.sscc.ssccopsserver.support.AssistantGoldenSet.CURRENT_PDF_ID;
import static org.sscc.ssccopsserver.support.AssistantGoldenSet.CURRENT_PDF_NAME;
import static org.sscc.ssccopsserver.support.AssistantGoldenSet.GUIDELINE_ID;
import static org.sscc.ssccopsserver.support.AssistantGoldenSet.GUIDELINE_NAME;
import static org.sscc.ssccopsserver.support.AssistantGoldenSet.LEXICAL_NEAR_MISSES;
import static org.sscc.ssccopsserver.support.AssistantGoldenSet.REGULATION_ID;
import static org.sscc.ssccopsserver.support.AssistantGoldenSet.REGULATION_NAME;
import static org.sscc.ssccopsserver.support.AssistantGoldenSet.TYPE_COMPARISON;
import static org.sscc.ssccopsserver.support.AssistantGoldenSet.UNANSWERABLE;
import static org.sscc.ssccopsserver.support.AssistantGoldenSet.currentPdfChunks;
import static org.sscc.ssccopsserver.support.AssistantGoldenSet.guidelineChunks;
import static org.sscc.ssccopsserver.support.AssistantGoldenSet.markerOf;
import static org.sscc.ssccopsserver.support.AssistantGoldenSet.regulationChunks;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.sscc.ssccopsserver.domain.assistant.code.CitationType;
import org.sscc.ssccopsserver.domain.assistant.code.RagApplyStatus;
import org.sscc.ssccopsserver.domain.assistant.code.RagDocumentType;
import org.sscc.ssccopsserver.domain.assistant.dto.AssistantCitationResponse;
import org.sscc.ssccopsserver.domain.assistant.dto.AssistantQueryRequest;
import org.sscc.ssccopsserver.domain.assistant.dto.AssistantQueryResponse;
import org.sscc.ssccopsserver.domain.assistant.entity.RagDocumentEntity;
import org.sscc.ssccopsserver.domain.assistant.repository.RagDocumentRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.support.AssistantGoldenSet.Golden;
import org.sscc.ssccopsserver.support.LexicalRagChunkStore;

import reactor.core.publisher.Flux;

/*
 * 검색·답변 골든셋 — **실제 문서 세 벌 · 스텁 임베딩 · Gemini 없음** (#405 · #447 · 기획안 §14.2).
 *
 * ══ 무엇을 재는 자리인가 ═══════════════════════════════════════
 *
 * 파서 골든셋(`RegulationGoldenSetTest` · `GenericGoldenSetTest`)이 «문서가 청크로 옳게
 * 갈리는가»를 봤다면, 여기는 **그 청크가 질문 하나에 대해 옳게 골라지고 옳은 인용이 되어
 * 나오는가**를 본다. 네 지표가 기획안 §14.2의 값 그대로다:
 *
 * | 지표 | 목표 | 어디서 |
 * |---|---|---|
 * | 검색 적중 hit@5 | ≥ 0.9 | {@link #retrievesTheExpectedEvidenceWithinTheTopFive()} |
 * | 인용 정확도 | ≥ 0.95 | {@link #citesTheExpectedEvidence()} |
 * | **올바른 거절률** | **1.0** | {@link #refusesEveryQuestionTheCorpusCannotAnswer()} |
 * | p95 질의 응답 시간 | < 5s | {@link #staysWellUnderTheLatencyBudget()} |
 *
 * ══ 번호 참조 뒤 «인용 정확도»가 재는 것 (#447) ═════════════════
 *
 * 시험지의 정답지({@code AssistantGoldenSet.ANSWERABLE})는 여전히 `제7조`·`부칙 제3조` 같은
 * **표기 문자열**이다. 달라진 것은 **누가 그 문자열을 썼는가**다 — 예전에는 모델의 출력이었고
 * 지금은 서버가 청크에서 만든 값이다({@code AssistantCitationResponse.marker()}). 그래서 이
 * 지표는 「모델이 조 번호를 옳게 옮겨 적었는가」가 아니라 **「검색이 올려 준 근거가 모델이 고른
 * 번호를 거쳐 옳은 인용으로 오는가」**를 잰다. 앞의 물음은 번호 참조로 **사라진 물음**이다 —
 * 모델이 쓸 수 있는 값이 `1..N`뿐이라 틀린 조 번호가 발생할 자리가 없다.
 *
 * ══ «스텁 임베딩»이 무엇이고 무엇을 못 하는가 ═══════════════════
 *
 * {@link LexicalRagChunkStore}가 문자 bigram TF-IDF 코사인으로 순위를 만든다 — 결정적이라 CI에서
 * 매번 돌고 쿼터를 쓰지 않는다(§14.2가 실제 모델 품질을 CI에 넣지 않기로 한 자리).
 *
 * ⚠️ **그 눈금으로 운영 임계값을 정할 수 없다.** 어휘가 겹치기만 해도 점수가 오르기 때문이며,
 * {@link #theStubScaleCannotTellLexicalNearMissesApart()}가 그 사실을 실제 숫자로 못 박는다 —
 * 그래서 `AssistantQueryPolicy`의 두 기본값은 **이 이슈에서도 같은 값으로 남는다.** 실제 눈금은
 * 실제 키로 재며 그 자리가 `./gradlew geminiCheck` 의 7단계다.
 *
 * **골든셋이 실제로 정한 것은 방향이다** — 같은 질문·같은 코퍼스에서 조 단위 청크가 고정 길이
 * 청크보다 높게 나온다({@link #articleChunksOutscoreFixedLengthOnesForTheSameQuestion()}).
 * 유형별 손잡이가 있어야 하는 이유이고, 움직일 때 어느 쪽을 낮춰야 하는지가 그 결과다.
 *
 * ══ 코퍼스가 둘인 이유 ═════════════════════════════════════════
 *
 * | | 무엇이 | 무엇을 위해 |
 * |---|---|---|
 * | **A** | 개정안 `.md`(STRUCTURED) + 학술국 운영 세칙 `.docx`(GENERIC · 쪽 없음) | 조항 인용 · 부칙 · 가지 조번호 · 문서명 인용 · 거절 |
 * | **B** | 개정안 `.md`(STRUCTURED) + 현행 회칙 `.pdf`(GENERIC · 쪽 있음) | `p.N` 인용 · 혼합 인용 · 유형별 점수 비교 |
 *
 * **B가 판본이 어긋나는 두 문서를 일부러 함께 담는다.** 운영 코퍼스에서는 하지 않는 일이고
 * (ssccops#323 — 현행 회칙은 올리지 않는다) 그래서 `GenericGoldenSetTest`도 그 PDF를 «쪽이
 * 나오는가»의 증인으로만 썼는데, 여기서는 **바로 그 «같은 내용, 다른 청킹»이 필요하다** —
 * 유형별 점수 차이를 재려면 idf가 같은 한 코퍼스 안에서 같은 질문을 물어야 한다.
 */
class RetrievalGoldenSetTest {

    // ------------------------------------------------------------------ 코퍼스

    /*
     * **시험지와 코퍼스는 {@link AssistantGoldenSet}가 갖는다** — 실제 키로 눈금을 재는
     * `./gradlew geminiCheck` 가 **같은 질문·같은 청크**를 물어야 그 값이 여기의 값과 이어진다.
     */
    private static final RagDocumentEntity REGULATION =
            document(REGULATION_ID, "REGULATION", REGULATION_NAME, RagDocumentType.STRUCTURED);
    private static final RagDocumentEntity GUIDELINE =
            document(GUIDELINE_ID, "GUIDELINE", GUIDELINE_NAME, RagDocumentType.GENERIC);
    private static final RagDocumentEntity CURRENT_PDF =
            document(CURRENT_PDF_ID, "SCHOOL_RULE", CURRENT_PDF_NAME, RagDocumentType.GENERIC);

    private static final LexicalRagChunkStore CORPUS_A =
            corpus(regulationChunks(), guidelineChunks());
    private static final LexicalRagChunkStore CORPUS_B =
            corpus(regulationChunks(), currentPdfChunks());

    // ------------------------------------------------------------------ 점수 분포에서 나온 임계값

    /** 답해야 하는 질문에서 <b>기대 인용의 청크가 받은 가장 낮은 점수</b> */
    private static final double LOWEST_EXPECTED = lowestExpectedScore();

    /** 거절해야 하는 질문이 받은 <b>가장 높은 점수</b> */
    private static final double HIGHEST_UNANSWERABLE = highestUnanswerableScore();

    /*
     * 둘 사이의 기하 평균. **골든셋이 임계값을 «정한다»는 것이 이 계산이다**(§14.2) — 다만 그
     * 값은 이 스텁의 눈금이라 운영 기본값이 되지 못한다(클래스 주석의 ⚠️).
     */
    private static final double THRESHOLD = Math.sqrt(LOWEST_EXPECTED * HIGHEST_UNANSWERABLE);

    // ------------------------------------------------------------------ 배선

    private final RagDocumentRepository ragDocumentRepository = mock(RagDocumentRepository.class);
    private final GoldenChatModel chatModel = new GoldenChatModel();
    private final AssistantQueryPolicy policy =
            new AssistantQueryPolicy(8, THRESHOLD, null, null, 1000, 200);

    private final MemberEntity member = member();

    @BeforeEach
    void reset() {
        chatModel.reset();
    }

    // ------------------------------------------------------------------ 지표 넷

    /*
     * **hit@5** — 기대 인용의 청크가 상위 다섯 안에 있는가. 임계값과 무관한 **순위만의** 값이라
     * 이 지표는 스텁의 눈금에 매이지 않는다.
     */
    @Test
    void retrievesTheExpectedEvidenceWithinTheTopFive() {
        int hits = 0;
        int expectations = 0;
        for (Golden golden : ANSWERABLE) {
            List<String> top = markers(CORPUS_A.scored(golden.question()), 5);
            for (String expected : golden.expected()) {
                expectations++;
                if (top.contains(expected)) {
                    hits++;
                }
            }
        }
        double hitAtFive = (double) hits / expectations;
        report("hit@5", "%.2f (%d/%d)".formatted(hitAtFive, hits, expectations));
        assertThat(hitAtFive).as("기획안 §14.2의 목표는 0.9다").isGreaterThanOrEqualTo(0.9);
    }

    /*
     * **인용 정확도** — 응답에 기대 인용이 실렸고, 실린 인용이 **전부 실제로 넣어 준 근거**인가.
     *
     * 스텁 모델은 상위 세 발췌를 인용한다(실제 모델이 가장 기대는 자리다). **«모델이 고른 인용이
     * 옳은가»는 여기서 답하지 않는다** — 그것이 모델 품질이고 CI에 넣지 않기로 한 것이다(§14.2).
     * 이 층이 재는 것은 「검색이 올려 준 근거가 인용까지 온전히 오는가」다.
     */
    @Test
    void citesTheExpectedEvidence() {
        int satisfied = 0;
        int expectations = 0;
        for (Golden golden : ANSWERABLE) {
            AssistantQueryResponse response = ask(CORPUS_A, golden.question());
            List<String> cited = citedMarkers(response);
            assertThat(response.answered()).as(golden.question()).isTrue();
            for (String expected : golden.expected()) {
                expectations++;
                if (cited.contains(expected)) {
                    satisfied++;
                }
            }
        }
        double accuracy = (double) satisfied / expectations;
        report("인용 정확도", "%.2f (%d/%d)".formatted(accuracy, satisfied, expectations));
        assertThat(accuracy).as("기획안 §14.2의 목표는 0.95다").isGreaterThanOrEqualTo(0.95);
    }

    /*
     * **올바른 거절률 1.0 — 이 기능에서 가장 중요한 값이다**(§6.1 · §14.2).
     *
     * 규정 답변에서 없는 조항을 지어내는 것은 틀린 답보다 나쁘다. 거절이 «모델을 부르지 않는
     * 것»이므로 호출 수 0을 함께 센다 — 1차 방어선의 정의가 그것이다.
     */
    @Test
    void refusesEveryQuestionTheCorpusCannotAnswer() {
        chatModel.invent();

        int refused = 0;
        for (String question : UNANSWERABLE) {
            AssistantQueryResponse response = ask(CORPUS_A, question);
            if (!response.answered()) {
                refused++;
            }
            assertThat(response.citations()).as(question).isEmpty();
        }
        report(
                "거절률",
                "%.2f (%d/%d)"
                        .formatted(
                                (double) refused / UNANSWERABLE.size(),
                                refused,
                                UNANSWERABLE.size()));
        assertThat(refused).isEqualTo(UNANSWERABLE.size());
        assertThat(chatModel.calls()).as("근거가 없으면 모델을 부르지 않는다 — 1차 방어선").isZero();
    }

    /*
     * **p95 < 5s.** 여기 실린 시간에는 Gemini 왕복이 없다 — 스텁 임베딩·스텁 모델이라 재는 것은
     * **우리 몫**(검색 · 임계값 · 프롬프트 조립 · 인용 검증)이다. 그 몫이 예산의 큰 부분을 먹기
     * 시작하면 이 값이 먼저 움직인다. 모델 왕복 쪽의 상한은 여기가 아니라
     * `ssccops.assistant.gemini.call-timeout`(기본 20초)이 건다.
     */
    @Test
    void staysWellUnderTheLatencyBudget() {
        List<Long> elapsed = new ArrayList<>();
        for (Golden golden : ANSWERABLE) {
            long startedAt = System.nanoTime();
            ask(CORPUS_A, golden.question());
            elapsed.add(System.nanoTime() - startedAt);
        }
        elapsed.sort(Long::compare);
        long p95 = elapsed.get((int) Math.ceil(elapsed.size() * 0.95) - 1);
        report("p95(모델 왕복 제외)", "%d ms".formatted(p95 / 1_000_000));
        assertThat(p95).as("기획안 §14.2의 목표는 5초다").isLessThan(5_000_000_000L);
    }

    // ------------------------------------------------------------------ 임계값과 점수 분포

    /*
     * **점수 분포에 임계값이 설 자리가 있다** — 답해야 하는 것의 최저가 거절해야 하는 것의
     * 최고보다 높다. 이것이 성립하지 않으면 위 세 지표는 «임계값을 무엇으로 두어도 하나는
     * 틀린다»는 뜻이라 함께 의미를 잃는다.
     */
    @Test
    void theScoreDistributionLeavesRoomForAThreshold() {
        report(
                "점수 분포",
                "기대 최저=%.4f  무관 최고=%.4f  → 임계값 %.4f"
                        .formatted(LOWEST_EXPECTED, HIGHEST_UNANSWERABLE, THRESHOLD));
        assertThat(LOWEST_EXPECTED).isGreaterThan(HIGHEST_UNANSWERABLE);
        assertThat(THRESHOLD).isStrictlyBetween(HIGHEST_UNANSWERABLE, LOWEST_EXPECTED);
    }

    /*
     * ⚠️ **이 스텁의 눈금으로는 운영 임계값을 정할 수 없다 — 그 증거가 이 테스트다.**
     *
     * 어휘만 겹치고 답을 담지 않은 질문(«동아리 티셔츠는 어디서 주문하나요?» → 제30조 동아리 등록)이
     * 답해야 하는 질문의 최저 점수를 **넘어선다.** 문자 n-gram이 재는 것이 어휘의 겹침뿐이라
     * 당연한 일이고, 밀집 임베딩은 이 자리에서 다르게 답한다.
     *
     * 그래서 `AssistantQueryPolicy`의 두 기본값은 이 이슈에서도 **같은 값으로 남는다** — 「아직
     * 실측하지 않았다」는 표시가 아직 유효하다. 실제 눈금은 `./gradlew geminiCheck` 가 재며,
     * 여기 적힌 세 질문이 그 도구가 들고 갈 시험지다.
     */
    @Test
    void theStubScaleCannotTellLexicalNearMissesApart() {
        double highestNearMiss =
                LEXICAL_NEAR_MISSES.stream()
                        .mapToDouble(question -> CORPUS_A.scored(question).get(0).score())
                        .max()
                        .orElseThrow();

        report("어휘 근접 최고", "%.4f (기대 최저 %.4f)".formatted(highestNearMiss, LOWEST_EXPECTED));
        assertThat(highestNearMiss)
                .as("겹치지 않으면 스텁만으로 임계값을 정해도 된다는 뜻이 되어 이 주석이 거짓이 된다")
                .isGreaterThan(LOWEST_EXPECTED);
    }

    /*
     * **같은 질문·같은 코퍼스에서 조 단위 청크가 고정 길이 청크보다 높게 나온다** — 유형별
     * 임계값이 있어야 하는 이유이자, 움직일 때 **`GENERIC` 쪽을 낮춰야 한다**는 근거다.
     *
     * 코퍼스 B가 판본이 어긋나는 두 문서를 일부러 함께 담는 이유가 여기다(클래스 주석): 같은
     * 내용이 한쪽에서는 조 단위(목표 450자)로, 다른 쪽에서는 고정 길이(600자 + overlap 100자)로
     * 갈려 있고 idf가 같아야 두 점수를 견줄 수 있다.
     */
    @Test
    void articleChunksOutscoreFixedLengthOnesForTheSameQuestion() {
        for (String question : TYPE_COMPARISON) {
            double structured = bestScoreOfType(question, RagDocumentType.STRUCTURED);
            double generic = bestScoreOfType(question, RagDocumentType.GENERIC);
            report(
                    "유형별",
                    "%-22s 조단위=%.4f 고정길이=%.4f 비=%.2f"
                            .formatted(question, structured, generic, structured / generic));
            assertThat(structured).as(question).isGreaterThan(generic);
        }
    }

    // ------------------------------------------------------------------ 인용의 모양 넷

    /* **부칙 제3조가 본칙 제3조가 아닌 자기 표기로 인용된다** — 부칙에서 조번호가 1로 리셋된다(§5.3) */
    @Test
    void citesASupplementaryArticleWithTheSupplementaryMarker() {
        AssistantQueryResponse response = ask(CORPUS_A, "본 회의 의결은 어떤 순서를 따르나요?");

        assertThat(citedMarkers(response)).contains("부칙 제3조");
        assertThat(response.citations())
                .filteredOn(AssistantCitationResponse::supplementary)
                .first()
                .satisfies(
                        citation -> {
                            assertThat(citation.citationType()).isEqualTo(CitationType.ARTICLE);
                            assertThat(citation.article())
                                    .as("부칙은 표기 안에 «부칙»을 품는다")
                                    .isEqualTo("부칙 제3조 (의결의 순서)");
                            assertThat(citation.docTitle()).isEqualTo(REGULATION_NAME);
                        });
    }

    /* **가지 조번호가 제27조와 별개의 근거로 인용된다** — `제27조의2`가 그대로 표기가 된다 */
    @Test
    void citesABranchArticleAsEvidenceOfItsOwn() {
        AssistantQueryResponse response = ask(CORPUS_A, "회원의 개인정보는 어떻게 관리하나요?");

        assertThat(citedMarkers(response)).contains("제27조의2");
        assertThat(response.citations())
                .extracting(AssistantCitationResponse::article)
                .contains("제27조의2 (개인정보의 보호)");
    }

    /*
     * **쪽이 없는 형식(DOCX)은 문서명이 인용 표기다**(#398) — `PAGE`인데 `page`가 비는 것이
     * 정상인 유일한 경우이고, 서버가 «1쪽»을 지어내지 않는다는 사실이 여기서 드러난다.
     */
    @Test
    void citesAPagelessDocumentByItsName() {
        AssistantQueryResponse response = ask(CORPUS_A, "학술 활동 지원금은 언제까지 정산해야 하나요?");

        assertThat(response.citations())
                .filteredOn(citation -> GUIDELINE_NAME.equals(citation.docTitle()))
                .first()
                .satisfies(
                        citation -> {
                            assertThat(citation.citationType()).isEqualTo(CitationType.PAGE);
                            assertThat(citation.page()).as("DOCX에는 쪽이 없다").isNull();
                            assertThat(citation.snippet()).contains("지원금");
                        });
    }

    /*
     * **한 답변에 조항 인용과 쪽 인용이 함께 실린다**(§6.3 — 두 유형이 섞이는 것이 정상이다).
     *
     * 판본 배지는 **첫 인용의 판본**에서 오므로 유사도가 가장 높은 쪽의 것이다 — 날짜 여럿을
     * 배지 하나에 담을 방법이 없기 때문이다.
     */
    @Test
    void mixesArticleAndPageCitationsInOneAnswer() {
        AssistantQueryResponse response = ask(CORPUS_B, "본 회의 회계연도는 언제부터인가요?");

        assertThat(response.answered()).isTrue();
        assertThat(response.citations())
                .extracting(AssistantCitationResponse::citationType)
                .contains(CitationType.ARTICLE, CitationType.PAGE);
        assertThat(citedMarkers(response)).contains("제18조", "p.4");
        assertThat(response.citations())
                .filteredOn(citation -> citation.citationType() == CitationType.PAGE)
                .first()
                .satisfies(
                        citation -> {
                            assertThat(citation.docTitle()).isEqualTo(CURRENT_PDF_NAME);
                            assertThat(citation.page()).isEqualTo(4);
                        });
        assertThat(response.applyStatus()).isEqualTo(RagApplyStatus.EFFECTIVE);
    }

    // ------------------------------------------------------------------ 3차 방어선

    /*
     * **범위를 벗어난 번호는 목록에서도 본문에서도 사라진다**(§6.3 · #447).
     *
     * 목록에서만 빼면 문장에 `[99]`가 남아 화면이 «근거가 있는 문장»으로 읽는다 — 인용 카드가
     * 없다는 것을 알아채는 사람은 없다. 맞는 인용까지 함께 버리지 않는 것도 같은 무게로 본다.
     */
    @Test
    void stripsInventedCitationsFromTheAnswerWhileKeepingTheRealOnes() {
        chatModel.invent();

        for (Golden golden : ANSWERABLE) {
            AssistantQueryResponse response = ask(CORPUS_A, golden.question());

            assertThat(response.answered()).as(golden.question()).isTrue();
            assertThat(response.answer())
                    .as(golden.question())
                    .doesNotContain("[99]", "[98]", "[97]");
            assertThat(response.citations())
                    .as(golden.question())
                    .extracting(AssistantCitationResponse::ref)
                    .allSatisfy(ref -> assertThat(ref).isBetween(1, policy.getTopK()));
            assertThat(citedMarkers(response))
                    .as(golden.question())
                    .containsAnyElementsOf(golden.expected());
        }
    }

    /*
     * **인용이 하나도 검증되지 않으면 답을 통째로 버린다** — 3차 방어선(§6.1). 사용자에게는
     * 「찾지 못했다」와 같은 문구이고, 모델을 한 번 불렀다는 사실은 로그에만 남는다.
     */
    @Test
    void discardsAnAnswerThatCitesNothingWeHandedOver() {
        chatModel.citeNothing();

        AssistantQueryResponse response = ask(CORPUS_A, "정회원으로 승격하려면 어떤 조건을 갖춰야 하나요?");

        assertThat(response.answered()).isFalse();
        assertThat(response.answer()).isEqualTo(AssistantPrompt.NO_EVIDENCE);
        assertThat(response.citations()).isEmpty();
        assertThat(response.applyStatus()).isNull();
        assertThat(chatModel.calls()).as("근거는 있었으므로 모델은 불렸다").isEqualTo(1);
    }

    // ------------------------------------------------------------------ 추천 질문

    /*
     * **추천 질문 셋이 실제로 답해진다** (§13.3의 ⚠️ · 이 이슈의 항목).
     *
     * 목업의 «출석률 미달 처리»·«지원금 한도와 정산 기한»은 학술국 운영 세칙·지원금 집행 지침이
     * 실재해야 답할 수 있어 코퍼스 조건(`doc_cd`)이 이미 그것을 막고 있다. 남는 것은 **회칙만으로
     * 답할 수 있는 셋**이고, 그 셋이 누르면 거절당하는 문장이 아니라는 것을 여기서 본다 —
     * 추천 질문을 눌렀는데 「찾지 못했습니다」가 돌아오는 화면이 §13.3이 막으려는 것이다.
     */
    @Test
    void answersEverySuggestionTheCorpusAdvertises() {
        List<String> suggestions = new AssistantSuggestions().forCorpus(true);

        assertThat(suggestions).hasSize(3);
        for (String suggestion : suggestions) {
            AssistantQueryResponse response = ask(CORPUS_A, suggestion);
            assertThat(response.answered()).as(suggestion).isTrue();
            assertThat(response.citations()).as(suggestion).isNotEmpty();
        }
    }

    // ------------------------------------------------------------------ 스트리밍 회귀 (#447)

    /*
     * **흘려보낸 답이 한 번에 받은 답과 같다 — 시험지 전체에서.**
     *
     * #447이 두 경로를 남겨 둔 대가가 «한쪽에만 생기는 회귀»인데, 지표를 재는 것은 한 번에 받는
     * 경로다(위 넷). 그래서 같은 질문들을 흘려보내며 한 번 더 물어 **답·인용·판본이 전부 같은지**
     * 본다 — 여기가 어긋나면 위의 지표가 화면이 보는 답을 더는 말하지 않는다.
     */
    @Test
    void streamingGivesTheSameAnswersAsTheOneShotPath() {
        for (Golden golden : ANSWERABLE) {
            AssistantQueryResponse atOnce = ask(CORPUS_A, golden.question());
            StreamCollector streamed = askStreaming(CORPUS_A, golden.question());

            assertThat(streamed.text()).as(golden.question()).isEqualTo(atOnce.answer());
            assertThat(streamed.done.answered()).as(golden.question()).isTrue();
            assertThat(streamed.done.citations())
                    .as(golden.question())
                    .isEqualTo(atOnce.citations());
            assertThat(streamed.done.applyStatus()).isEqualTo(atOnce.applyStatus());
            assertThat(streamed.done.effectiveDate()).isEqualTo(atOnce.effectiveDate());
        }
    }

    /* **근거가 없으면 한 글자도 나가지 않는다** — 임계값 거절은 스트림이 열리기 전에 끝난다 */
    @Test
    void streamsNothingAtAllForAQuestionTheCorpusCannotAnswer() {
        for (String question : UNANSWERABLE) {
            StreamCollector streamed = askStreaming(CORPUS_A, question);

            assertThat(streamed.deltas).as(question).isEmpty();
            assertThat(streamed.done.answered()).as(question).isFalse();
            assertThat(streamed.done.answer()).isEqualTo(AssistantPrompt.NO_EVIDENCE);
        }
        assertThat(chatModel.calls()).as("근거가 없으면 모델을 부르지 않는다 — 1차 방어선").isZero();
    }

    // ------------------------------------------------------------------ 픽스처

    private AssistantQueryResponse ask(LexicalRagChunkStore store, String question) {
        return service(store).query(new AssistantQueryRequest(question, null), member);
    }

    private AssistantServiceImpl service(LexicalRagChunkStore store) {
        RagDocumentEntity[] searchable =
                store == CORPUS_A
                        ? new RagDocumentEntity[] {REGULATION, GUIDELINE}
                        : new RagDocumentEntity[] {REGULATION, CURRENT_PDF};
        when(ragDocumentRepository.findSearchable()).thenReturn(List.of(searchable));

        return new AssistantServiceImpl(
                new AssistantFeature(true),
                policy,
                new AssistantRateLimiter(
                        10_000,
                        100_000,
                        100_000,
                        Clock.fixed(Instant.parse("2026-09-15T01:00:00Z"), ZoneOffset.UTC)),
                new AssistantSuggestions(),
                new CitationVerifier(policy),
                conversations(),
                ragDocumentRepository,
                provider(store),
                provider(ChatClient.builder(chatModel).build()));
    }

    /** 같은 질의를 흘려보내며 — 조각과 마지막 응답을 모은다 */
    private StreamCollector askStreaming(LexicalRagChunkStore store, String question) {
        StreamCollector collector = new StreamCollector();
        service(store).queryStreaming(new AssistantQueryRequest(question, null), member, collector);
        return collector.await();
    }

    /*
     * 대화는 **매번 새로 만든다** — 골든셋의 질문들은 서로 이어지지 않는 단발 질의이고(시험지가
     * 그렇게 짜여 있다 · `AssistantGoldenSet`), 한 벌을 나눠 쓰면 앞 질문의 답이 다음 질문의
     * 맥락으로 들어가 **지표가 질문 순서에 따라 달라진다**(#406).
     */
    private AssistantConversations conversations() {
        return new AssistantConversations(
                MessageWindowChatMemory.builder()
                        .chatMemoryRepository(
                                new AssistantMemoryStore(
                                        500,
                                        Duration.ofHours(24),
                                        Clock.fixed(
                                                Instant.parse("2026-09-15T01:00:00Z"),
                                                ZoneOffset.UTC)))
                        .maxMessages(40)
                        .build());
    }

    /*
     * 응답의 인용을 정답지와 같은 축으로 — <b>서버가 만든 표기 그대로다</b> (#447).
     *
     * 예전에는 `article`·`page`에서 표기 모양을 되짜 맞췄는데, 번호 참조로 바뀌며 그 표기가
     * <b>응답에 그대로 실려 온다</b>({@code marker}). 되짜 맞추는 코드를 남겨 두면 «서버가 만든
     * 표기»와 «테스트가 만든 표기» 둘이 되어, 정작 틀렸을 때 어느 쪽이 틀렸는지 알 수 없다.
     */
    private static List<String> citedMarkers(AssistantQueryResponse response) {
        return response.citations().stream().map(AssistantCitationResponse::marker).toList();
    }

    private static List<String> markers(List<LexicalRagChunkStore.Scored> scored, int count) {
        return scored.stream().limit(count).map(one -> markerOf(one.chunk())).toList();
    }

    private double bestScoreOfType(String question, RagDocumentType type) {
        return CORPUS_B.scored(question).stream()
                .filter(
                        one ->
                                type.name()
                                        .equals(
                                                one.chunk()
                                                        .getMetadata()
                                                        .get(RagChunkMetadata.DOC_TYPE)))
                .mapToDouble(LexicalRagChunkStore.Scored::score)
                .max()
                .orElseThrow();
    }

    private static double lowestExpectedScore() {
        double lowest = 1;
        for (Golden golden : ANSWERABLE) {
            List<LexicalRagChunkStore.Scored> scored = CORPUS_A.scored(golden.question());
            for (String expected : golden.expected()) {
                lowest =
                        Math.min(
                                lowest,
                                scored.stream()
                                        .filter(one -> expected.equals(markerOf(one.chunk())))
                                        .mapToDouble(LexicalRagChunkStore.Scored::score)
                                        .max()
                                        .orElse(0));
            }
        }
        return lowest;
    }

    private static double highestUnanswerableScore() {
        return UNANSWERABLE.stream()
                .mapToDouble(question -> CORPUS_A.scored(question).get(0).score())
                .max()
                .orElseThrow();
    }

    private static void report(String label, String value) {
        System.out.printf("[골든셋] %-16s %s%n", label, value);
    }

    private static LexicalRagChunkStore corpus(List<Document> first, List<Document> second) {
        LexicalRagChunkStore store = new LexicalRagChunkStore();
        store.add(first);
        store.add(second);
        return store;
    }

    private static RagDocumentEntity document(
            long id, String code, String name, RagDocumentType type) {
        RagDocumentEntity document = mock(RagDocumentEntity.class);
        when(document.getId()).thenReturn(id);
        when(document.getName()).thenReturn(name);
        when(document.getType()).thenReturn(type);
        when(document.getApplyStatus()).thenReturn(RagApplyStatus.EFFECTIVE);
        when(document.getEffectiveFrom()).thenReturn(LocalDate.of(2026, 3, 24));
        return document;
    }

    private static MemberEntity member() {
        MemberEntity mock = mock(MemberEntity.class);
        when(mock.getId()).thenReturn(1L);
        return mock;
    }

    /** 흘러나온 조각과 마지막 응답 — 컨트롤러가 SSE 로 하는 일의 알맹이만 */
    private static final class StreamCollector implements AssistantAnswerSink {

        private final List<String> deltas = new ArrayList<>();
        private final CountDownLatch finished = new CountDownLatch(1);
        private AssistantQueryResponse done;

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
        public void failed(org.sscc.ssccopsserver.global.apipayload.code.error.ErrorCode code) {
            finished.countDown();
            throw new AssertionError("골든셋에서 모델이 실패할 자리가 없다 — " + code.getCode());
        }

        StreamCollector await() {
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

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }

    /*
     * 모델 자리의 스텁 — **프롬프트에 찍힌 발췌 번호를 읽어 그대로 옮겨 쓴다** (#447).
     *
     * 발췌 목록을 밖에서 받지 않고 프롬프트에서 읽는 것이 요점이다: 실제 모델이 보는 것이 그
     * 문자열뿐이라, 프롬프트가 번호를 찍어 주지 않게 되는 순간 이 스텁도 인용하지 못한다 —
     * 「발췌마다 번호를 찍는다」가 인용 해석의 전제이므로(`AssistantPrompt.user`) 그 전제가
     * 깨지면 골든셋이 먼저 무너지는 것이 맞다.
     *
     * 날조하는 모델은 **범위 밖 번호**를 덧붙인다. 옛 계약에서는 `[제99조]`처럼 없는 조를 지어낼
     * 수 있었는데 번호 참조에서는 그 자리가 «있지도 않은 발췌를 가리킨다»로 바뀌었다 — 지어낼
     * 수 있는 것의 모양이 이렇게까지 좁아진 것이 #447이 한 일이다.
     */
    private static final class GoldenChatModel implements ChatModel {

        /** 실제 모델이 가장 기대는 자리 — 상위 셋만 인용한다. 전부 인용하면 인용 정확도가 검색 적중과 같은 값이 된다 */
        private static final int CITED = 3;

        private static final Pattern EXCERPT = Pattern.compile("--- 발췌 (\\d+) ---");

        private static final List<String> INVENTED = List.of("99", "98", "97");

        private int calls;
        private boolean invent;
        private boolean citeNothing;

        @Override
        public ChatResponse call(Prompt prompt) {
            calls++;
            StringBuilder answer = new StringBuilder("규정에 따르면 다음과 같습니다.");
            if (!citeNothing) {
                for (String number : numbersIn(prompt)) {
                    answer.append(" 자세한 내용은 [").append(number).append("]에 있습니다.");
                }
                if (invent) {
                    for (String number : INVENTED) {
                        answer.append(" 그리고 [").append(number).append("]도 함께 봅니다.");
                    }
                }
            }
            return new ChatResponse(
                    List.of(new Generation(new AssistantMessage(answer.toString()))));
        }

        /** 조각으로 흘려보내는 쪽 — 한 글자씩 낸다. 토큰이 조각 경계에 걸치는 것이 요점이다(#447) */
        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            String answer = call(prompt).getResult().getOutput().getText();
            calls--; // call(...)이 이미 세었다
            return Flux.fromStream(answer.chars().mapToObj(Character::toString))
                    .map(
                            piece ->
                                    new ChatResponse(
                                            List.of(new Generation(new AssistantMessage(piece)))));
        }

        private List<String> numbersIn(Prompt prompt) {
            List<String> numbers = new ArrayList<>();
            for (Message message : prompt.getInstructions()) {
                Matcher matcher =
                        EXCERPT.matcher(message.getText() == null ? "" : message.getText());
                while (matcher.find() && numbers.size() < CITED) {
                    numbers.add(matcher.group(1));
                }
            }
            return numbers;
        }

        void invent() {
            invent = true;
        }

        void citeNothing() {
            citeNothing = true;
        }

        int calls() {
            return calls;
        }

        void reset() {
            calls = 0;
            invent = false;
            citeNothing = false;
        }
    }
}
