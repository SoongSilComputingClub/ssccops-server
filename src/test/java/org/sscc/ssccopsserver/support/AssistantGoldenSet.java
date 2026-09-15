package org.sscc.ssccopsserver.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.ai.document.Document;
import org.sscc.ssccopsserver.domain.assistant.code.RagApplyStatus;
import org.sscc.ssccopsserver.domain.assistant.service.DocumentChunker;
import org.sscc.ssccopsserver.domain.assistant.service.GenericTextExtractor;
import org.sscc.ssccopsserver.domain.assistant.service.RagChunkMetadata;
import org.sscc.ssccopsserver.domain.assistant.service.RagChunkStore;
import org.sscc.ssccopsserver.domain.assistant.service.RegulationChunker;
import org.sscc.ssccopsserver.domain.assistant.service.RegulationParser;

/*
 * 검색·답변 골든셋의 **시험지 한 벌** (#405 · 기획안 §14.2).
 *
 * ══ 왜 테스트 바깥에 있는가 ════════════════════════════════════
 *
 * 같은 질문을 **두 곳이 묻는다.** {@code RetrievalGoldenSetTest}는 스텁 임베딩으로 CI 에서 매번
 * 묻고, {@code GeminiCheck}(`./gradlew geminiCheck`)는 실제 키로 눈금을 잰다. 두 벌로 두면
 * 「스텁에서는 통과하는데 실측은 다른 질문을 본」 상태가 조용히 성립하고, 그러면 실측값으로
 * 임계값을 정할 근거가 사라진다 — 임계값이 이 표에 매여 있다는 것이 §14.2의 전제다.
 *
 * ══ 코퍼스가 둘이다 ════════════════════════════════════════════
 *
 * | | 무엇이 | 무엇을 위해 |
 * |---|---|---|
 * | **A** | 개정안 `.md`(STRUCTURED) + 학술국 운영 세칙 `.docx`(GENERIC · 쪽 없음) | 조항 인용 · 부칙 · 가지 조번호 · 문서명 인용 · 거절 |
 * | **B** | 개정안 `.md`(STRUCTURED) + 현행 회칙 `.pdf`(GENERIC · 쪽 있음) | `p.N` 인용 · 혼합 인용 · 유형별 점수 비교 |
 *
 * **B가 판본이 어긋나는 두 문서를 일부러 함께 담는다.** 운영 코퍼스에서는 하지 않는 일이고
 * (ssccops#323 — 현행 회칙은 올리지 않는다) `GenericGoldenSetTest`도 그 PDF를 «쪽이 나오는가»의
 * 증인으로만 썼는데, 여기서는 **그 «같은 내용, 다른 청킹»이 필요하다** — 유형별 점수 차이를
 * 재려면 같은 코퍼스 안에서(= 같은 idf, 같은 모델) 같은 질문을 물어야 한다.
 *
 * 리소스 셋의 출처와 «왜 이 파일인가»는 `RegulationGoldenSetTest`·`GenericGoldenSetTest`의
 * 주석에 있다 — 여기서는 그것을 청크로 만들기만 한다.
 */
public final class AssistantGoldenSet {

    public static final String REGULATION_NAME = "SSCC 동아리 회칙 (2026 개강총회 개정안)";
    public static final String GUIDELINE_NAME = "학술국 운영 세칙";
    public static final String CURRENT_PDF_NAME = "2026년도 학술분과 SSCC 동아리회칙";

    public static final long REGULATION_ID = 1L;
    public static final long GUIDELINE_ID = 2L;
    public static final long CURRENT_PDF_ID = 3L;

    /*
     * 답해야 하는 질문과 **그 답이 기대는 인용 표기**. 표기는 모델이 옮겨 쓰는 문자열 그대로이며
     * (`RetrievedChunk.marker()`), 그래서 이 표가 곧 인용 정확도의 정답지다.
     *
     * 앞의 셋은 **추천 질문 표(`AssistantSuggestions`)의 회칙 질문 셋과 같은 문장**이다 — 그
     * 문구를 확정하는 것이 #405의 항목이라(§13.3의 ⚠️) 실제로 답해지는지를 함께 본다.
     */
    public static final List<Golden> ANSWERABLE =
            List.of(
                    new Golden("정회원으로 승격하려면 어떤 조건을 갖춰야 하나요?", "제7조"),
                    new Golden("회칙을 개정하려면 어떤 절차를 거치나요?", "제24조", "제23조"),
                    new Golden("임원이 임기 중에 그만두면 회원 등급은 어떻게 되나요?", "제7조"),
                    new Golden("본 회의 의결은 어떤 순서를 따르나요?", "부칙 제3조"),
                    new Golden("회원의 개인정보는 어떻게 관리하나요?", "제27조의2"),
                    new Golden("임원을 탄핵하려면 어떤 요건이 필요한가요?", "제14조"),
                    new Golden("학술 활동 지원금은 언제까지 정산해야 하나요?", GUIDELINE_NAME),
                    new Golden("스터디 세부 규정은 어디에 있고 지원금 정산 기한은 언제인가요?", "제27조", GUIDELINE_NAME));

    /*
     * 거절해야 하는 질문 — **코퍼스에 어휘조차 없는 주제**다(§14.2의 「코퍼스에 없는 주제」).
     *
     * 어휘 근접 질문을 여기 섞지 않은 것은 스텁 임베딩이 그것을 걸러 내지 못하기 때문이고,
     * **그 사실 자체는 지우지 않고 {@link #LEXICAL_NEAR_MISSES}로 따로 못 박는다.**
     */
    public static final List<String> UNANSWERABLE =
            List.of("기숙사 통금 시간은 몇 시인가요?", "교내 셔틀버스 배차 간격이 어떻게 되나요?", "노트북 수리는 어디에 맡기나요?");

    /**
     * 어휘만 겹치고 답을 담지 않은 질문.
     *
     * <p>«동아리 티셔츠는 어디서 주문하나요?» 는 제30조(동아리 등록)와 어휘가 겹친다. <b>문자 n-gram 스텁은 이것을 거르지 못하고, 밀집 임베딩은 이
     * 자리에서 다르게 답해야 한다</b> — 그 차이를 재는 것이 실측 도구가 하는 일이다.
     */
    public static final List<String> LEXICAL_NEAR_MISSES =
            List.of("동아리 티셔츠는 어디서 주문하나요?", "학생 식당 오늘 메뉴가 뭔가요?", "주차장 이용 요금은 얼마인가요?");

    /** 같은 내용이 두 청킹으로 갈려 있을 때 견주는 질문 — 코퍼스 B 전용이다 */
    public static final List<String> TYPE_COMPARISON =
            List.of(
                    "회칙 개정은 어떻게 의결하나요?",
                    "회원의 등급에는 어떤 것이 있나요?",
                    "본 회의 회계연도는 언제부터인가요?",
                    "동기회는 어떻게 설치하나요?");

    private AssistantGoldenSet() {}

    /** 질문 하나와 <b>그 답이 기대는 인용 표기</b> */
    public record Golden(String question, List<String> expected) {

        public Golden(String question, String... expected) {
            this(question, List.of(expected));
        }
    }

    /** 개정안 `.md` — 조 단위 청크 */
    public static List<Document> regulationChunks() {
        String markdown =
                new String(read("/rag/regulation-2026-amendment.md"), StandardCharsets.UTF_8);
        return chunker()
                .documents(
                        new RegulationParser().parse(markdown),
                        REGULATION_ID,
                        RagApplyStatus.EFFECTIVE);
    }

    /** 학술국 운영 세칙 `.docx` — 고정 길이 청크, <b>쪽이 없다</b> */
    public static List<Document> guidelineChunks() {
        return chunker()
                .documents(
                        new GenericTextExtractor()
                                .extract(
                                        read("/rag/guideline-sample.docx"),
                                        "guideline-sample.docx"),
                        GUIDELINE_NAME,
                        GUIDELINE_ID,
                        RagApplyStatus.EFFECTIVE);
    }

    /** 현행 회칙 `.pdf` — 고정 길이 청크, 쪽이 있다 */
    public static List<Document> currentPdfChunks() {
        return chunker()
                .documents(
                        new GenericTextExtractor()
                                .extract(
                                        read("/rag/regulation-current.pdf"),
                                        "regulation-current.pdf"),
                        CURRENT_PDF_NAME,
                        CURRENT_PDF_ID,
                        RagApplyStatus.EFFECTIVE);
    }

    /**
     * 청크 하나가 낳을 인용 표기 — {@code RetrievedChunk.marker()}와 같은 규칙이다.
     *
     * <p>그쪽을 부르지 않는 것은 그 메서드가 판본({@code SearchableDocument})을 함께 요구하기 때문이고, 여기서는 아직 판본이 없는 청크만 들고
     * 있다. 규칙이 갈리면 골든셋의 정답지가 실제 표기와 어긋나므로 <b>바뀌면 함께 고칠 것.</b>
     */
    public static String markerOf(Document chunk) {
        Object label = chunk.getMetadata().get(RagChunkMetadata.ARTICLE_LABEL);
        if (label != null) {
            return Boolean.parseBoolean(
                            String.valueOf(chunk.getMetadata().get(RagChunkMetadata.SUPPLEMENTARY)))
                    ? "부칙 " + label
                    : String.valueOf(label);
        }
        Object page = chunk.getMetadata().get(RagChunkMetadata.PAGE);
        if (page != null) {
            return "p." + page;
        }
        long ragDocId =
                ((Number) chunk.getMetadata().get(RagChunkStore.RAG_DOCUMENT_ID_KEY)).longValue();
        if (ragDocId == GUIDELINE_ID) {
            return GUIDELINE_NAME;
        }
        return ragDocId == CURRENT_PDF_ID ? CURRENT_PDF_NAME : REGULATION_NAME;
    }

    private static DocumentChunker chunker() {
        return new DocumentChunker(new RegulationChunker());
    }

    private static byte[] read(String resource) {
        try (InputStream stream = AssistantGoldenSet.class.getResourceAsStream(resource)) {
            return stream.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
