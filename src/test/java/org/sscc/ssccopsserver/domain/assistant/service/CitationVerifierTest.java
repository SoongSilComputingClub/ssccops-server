package org.sscc.ssccopsserver.domain.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.sscc.ssccopsserver.domain.assistant.code.CitationType;
import org.sscc.ssccopsserver.domain.assistant.code.RagApplyStatus;
import org.sscc.ssccopsserver.domain.assistant.code.RagDocumentType;
import org.sscc.ssccopsserver.domain.assistant.dto.AssistantCitationResponse;

/*
 * 인용 해석 — **없는 조를 인용하는 순간 이 기능의 값이 사라진다** (#403 · #447 · 기획안 §6.3 · §14.2).
 *
 * 여기서 보는 것은 «모델이 무엇을 쓰든 코드가 확인한 것만 남는가»다. 모델을 부르지 않으므로
 * CI에서 매번 돈다 — 실제 모델 품질은 골든셋(#405)의 몫이고 이 층은 **모델이 규칙을 어겼을 때**
 * 무슨 일이 일어나는지를 못 박는다.
 *
 * #447로 계약이 «모델이 조 문자열을 쓴다»에서 **«모델이 발췌 번호를 쓴다»**로 바뀌었다. 그래서
 * 이 클래스의 절반은 «범위 검사»이고, 나머지 절반은 **조각으로 나눠 들어와도 같은 답이 나오는가**다
 * — 스트리밍이 그 위에 서 있다.
 */
class CitationVerifierTest {

    private final CitationVerifier verifier =
            new CitationVerifier(new AssistantQueryPolicy(8, 0.5, null, null, 1000, 200));

    private final SearchableDocument regulation =
            new SearchableDocument(
                    1L,
                    "SSCC 동아리 회칙",
                    RagDocumentType.STRUCTURED,
                    RagApplyStatus.EFFECTIVE,
                    LocalDate.of(2026, 3, 24));

    private final SearchableDocument guideline =
            new SearchableDocument(
                    2L,
                    "2026 지원금 집행 지침",
                    RagDocumentType.GENERIC,
                    RagApplyStatus.EFFECTIVE,
                    LocalDate.of(2026, 3, 1));

    // ------------------------------------------------------------------ 번호가 곧 인용이다

    /*
     * **번호가 발췌를 가리키고, 조 표기는 서버가 붙인다** (#447).
     *
     * 모델이 쓴 것은 `[1]` 넉 자뿐이다 — `제7조 (회원의 구분)`도 `제2장 회원`도 우리가 청크에서
     * 꺼내 실었다. 그래서 **틀린 조 번호가 발생할 수 없다.**
     */
    @Test
    void resolvesAnExcerptNumberIntoTheCitationTheServerWrites() {
        List<RetrievedChunk> chunks = List.of(article(7, null, "제7조", "회원의 구분", "6항 정회원은 …"));

        CitationVerifier.Verified verified = verifier.verify("정회원 승격은 총회의 동의가 필요합니다. [1]", chunks);

        assertThat(verified.answer()).isEqualTo("정회원 승격은 총회의 동의가 필요합니다. [1]");
        assertThat(verified.dropped()).isZero();
        assertThat(verified.responses()).hasSize(1);

        AssistantCitationResponse citation = verified.responses().get(0);
        assertThat(citation.ref()).as("본문의 [1]과 짝이다").isEqualTo(1);
        assertThat(citation.marker()).as("서버가 만든 짧은 표기").isEqualTo("제7조");
        assertThat(citation.citationType()).isEqualTo(CitationType.ARTICLE);
        assertThat(citation.docTitle()).isEqualTo("SSCC 동아리 회칙");
        assertThat(citation.chapter()).isEqualTo("제2장 회원");
        assertThat(citation.supplementary()).isFalse();
        assertThat(citation.article()).isEqualTo("제7조 (회원의 구분)");
        assertThat(citation.clause()).as("번호 참조에는 항 정보가 없다 — 지어내지 않는다").isNull();
        assertThat(citation.page()).as("ARTICLE이면 page는 null이다 — 서버가 대체값을 만들지 않는다").isNull();
        assertThat(citation.snippet()).isEqualTo("6항 정회원은 …");
    }

    /*
     * **번호는 순서가 아니라 «몇 번째 발췌인가»다.** 여덟 개 중 둘만 인용되면 그 둘의 `ref`가
     * 3과 7이며 1·2로 다시 매기지 않는다 — 다시 매기려면 이미 흘려보낸 본문을 고쳐 써야 한다.
     */
    @Test
    void keepsTheExcerptNumberInsteadOfRenumberingTheCitations() {
        List<RetrievedChunk> chunks =
                List.of(
                        article(1, null, "제1조", "명칭", "본 회의 명칭은 …"),
                        article(7, null, "제7조", "회원의 구분", "6항 정회원은 …"),
                        page(12, "정산 기한은 …"));

        CitationVerifier.Verified verified = verifier.verify("그렇습니다 [2] 그리고 [3]", chunks);

        assertThat(verified.responses())
                .extracting(AssistantCitationResponse::ref)
                .containsExactly(2, 3);
        assertThat(verified.answer()).isEqualTo("그렇습니다 [2] 그리고 [3]");
    }

    /*
     * **범위 밖의 번호는 목록에서 빠질 뿐 아니라 본문에서도 지워진다.**
     *
     * 목록에서만 빼면 답변 문장에 `[9]`가 남아 화면이 «근거가 있는 문장»으로 읽는다 — 인용 카드가
     * 없다는 것을 알아채는 사람은 없다. 앞의 공백까지 함께 지우는 것은 «문장입니다 .»를 남기지
     * 않기 위해서다.
     */
    @Test
    void dropsAnOutOfRangeNumberFromTheAnswerItself() {
        List<RetrievedChunk> chunks = List.of(article(7, null, "제7조", "회원의 구분", "6항 정회원은 …"));

        CitationVerifier.Verified verified = verifier.verify("정회원 승격은 총회의 동의가 필요합니다. [9]", chunks);

        assertThat(verified.responses()).isEmpty();
        assertThat(verified.dropped()).isEqualTo(1);
        assertThat(verified.answer()).isEqualTo("정회원 승격은 총회의 동의가 필요합니다.");
    }

    /*
     * **옛 계약의 조·쪽 표기는 이제 어느 것도 통과하지 못하고 본문에서도 사라진다** (#447).
     *
     * 모델이 프롬프트를 어기고 `[제7조]`라고 써도 «맞는 인용»이 되지 않는다 — 통과시키면 조
     * 문자열을 서버가 쓴다는 계약이 문장 안에서 무너지고, 남겨 두면 카드 없는 근거가 된다.
     */
    @Test
    void refusesTheOldArticleAndPageMarkersAltogether() {
        List<RetrievedChunk> chunks =
                List.of(article(7, null, "제7조", "회원의 구분", "6항 정회원은 …"), page(12, "정산 기한은 …"));

        CitationVerifier.Verified verified =
                verifier.verify("승격은 [제7조] 이고 정산은 [p.12] 입니다. [부칙 제3조]", chunks);

        assertThat(verified.responses()).isEmpty();
        assertThat(verified.dropped()).isEqualTo(3);
        assertThat(verified.answer()).isEqualTo("승격은 이고 정산은 입니다.");
    }

    /* 넣어 준 발췌의 **문서명**도 같다 — 옛 계약에서는 통과하던 표기라 그대로 두면 카드 없는 근거가 된다 */
    @Test
    void refusesTheDocumentNameAsACitation() {
        List<RetrievedChunk> chunks = List.of(page(12, "정산 기한은 …"));

        CitationVerifier.Verified verified =
                verifier.verify("정산 기한은 30일입니다. [2026 지원금 집행 지침]", chunks);

        assertThat(verified.responses()).isEmpty();
        assertThat(verified.dropped()).isEqualTo(1);
        assertThat(verified.answer()).isEqualTo("정산 기한은 30일입니다.");
    }

    /* 인용이려던 것이 아닌 대괄호는 건드리지 않는다 — 지우는 것은 출처를 달려다 실패한 토큰뿐이다 */
    @Test
    void leavesBracketsThatAreNotCitationsAlone() {
        CitationVerifier.Verified verified =
                verifier.verify("[참고] 규정 문서에서 근거를 찾지 못했습니다.", List.of());

        assertThat(verified.answer()).isEqualTo("[참고] 규정 문서에서 근거를 찾지 못했습니다.");
        assertThat(verified.dropped()).isZero();
    }

    /* 발췌를 하나도 넣어 주지 않았으면 어떤 번호도 통과하지 못한다 */
    @Test
    void verifiesNothingWithoutExcerpts() {
        CitationVerifier.Verified verified = verifier.verify("제7조에 따르면 그렇습니다. [1]", List.of());

        assertThat(verified.responses()).isEmpty();
        assertThat(verified.answer()).isEqualTo("제7조에 따르면 그렇습니다.");
    }

    /* 같은 발췌를 두 번 인용해도 카드는 하나다 — 화면이 같은 조문을 두 번 그리지 않는다 */
    @Test
    void doesNotCarryTheSameEvidenceTwice() {
        List<RetrievedChunk> chunks = List.of(article(7, null, "제7조", "회원의 구분", "6항 정회원은 …"));

        assertThat(verifier.verify("[1] 그리고 [1]", chunks).responses()).hasSize(1);
    }

    // ------------------------------------------------------------------ 표기는 서버가 만든다

    /*
     * **부칙 여부가 표기에 들어간다** — 부칙에서 조번호가 1로 리셋되므로 `제1조`만으로는 본칙의
     * 제1조(명칭)와 갈리지 않는다(§5.3). 모델은 둘을 구별할 필요조차 없다: 번호가 다르다.
     */
    @Test
    void tellsSupplementaryArticlesApartFromTheMainBody() {
        List<RetrievedChunk> chunks =
                List.of(
                        article(1, null, "제1조", "명칭", "본 회의 명칭은 …"),
                        supplementaryArticle(1, "제1조", "용어", "이 부칙에서 쓰는 용어는 …"));

        AssistantCitationResponse main = verifier.verify("[1]", chunks).responses().get(0);
        assertThat(main.supplementary()).isFalse();
        assertThat(main.marker()).isEqualTo("제1조");

        AssistantCitationResponse supplement = verifier.verify("[2]", chunks).responses().get(0);
        assertThat(supplement.supplementary()).isTrue();
        assertThat(supplement.marker()).isEqualTo("부칙 제1조");
        assertThat(supplement.article()).isEqualTo("부칙 제1조 (용어)");
    }

    /* 가지 조번호는 그대로 표기가 된다 — `제27조의2`가 `제27조`와 별개의 조라는 사실이 표기에 남는다 */
    @Test
    void carriesABranchArticleNumberIntoTheMarker() {
        List<RetrievedChunk> chunks = List.of(article(27, 2, "제27조의2", "개인정보의 보호", "개인정보는 …"));

        AssistantCitationResponse citation = verifier.verify("[1]", chunks).responses().get(0);
        assertThat(citation.marker()).isEqualTo("제27조의2");
        assertThat(citation.article()).isEqualTo("제27조의2 (개인정보의 보호)");
    }

    /* `p.12`는 서버가 청크의 쪽 메타에서 만든다 — 모델이 쪽 번호를 쓸 일이 없다 */
    @Test
    void buildsPageMarkersFromTheChunkMetadata() {
        List<RetrievedChunk> chunks = List.of(page(12, "지원금은 활동 종료일로부터 30일 이내에 …"));

        AssistantCitationResponse citation = verifier.verify("[1]", chunks).responses().get(0);
        assertThat(citation.citationType()).isEqualTo(CitationType.PAGE);
        assertThat(citation.marker()).isEqualTo("p.12");
        assertThat(citation.page()).isEqualTo(12);
        assertThat(citation.docTitle()).isEqualTo("2026 지원금 집행 지침");
        assertThat(citation.article()).as("PAGE면 조항 쪽 필드가 전부 null이다").isNull();
        assertThat(citation.chapter()).isNull();
    }

    /*
     * **페이지가 없는 형식(DOCX)의 표기는 문서명이다**(#398 실측). 「전부 1쪽」을 지어내지 않으므로
     * 인용이 문서명까지만 가고, `page`가 `null`인 것이 **정상**이다.
     */
    @Test
    void marksAPagelessDocumentByItsName() {
        RetrievedChunk chunk =
                new RetrievedChunk(
                        Document.builder()
                                .text("학술국 운영 세칙\n출석률이 3분의 2에 미달하면 …")
                                .metadata(genericMetadata(null))
                                .score(0.7)
                                .build(),
                        new SearchableDocument(
                                3L,
                                "학술국 운영 세칙",
                                RagDocumentType.GENERIC,
                                RagApplyStatus.EFFECTIVE,
                                LocalDate.of(2026, 3, 1)));

        AssistantCitationResponse citation =
                verifier.verify("[1]", List.of(chunk)).responses().get(0);

        assertThat(citation.citationType()).isEqualTo(CitationType.PAGE);
        assertThat(citation.marker()).isEqualTo("학술국 운영 세칙");
        assertThat(citation.page()).isNull();
        assertThat(citation.docTitle()).isEqualTo("학술국 운영 세칙");
    }

    /* 한 답변에 두 유형이 섞이는 것이 정상이다 — 회칙이 세부 규정을 위임하고 있다(§6.3) */
    @Test
    void mixesArticleAndPageCitationsInOneAnswer() {
        List<RetrievedChunk> chunks =
                List.of(article(27, null, "제27조", "세부 규정", "세부 규정은 …"), page(12, "정산 기한은 …"));

        List<AssistantCitationResponse> citations =
                verifier.verify("세부 규정은 지침을 따릅니다. [1] 정산은 30일 이내입니다. [2]", chunks).responses();

        assertThat(citations).hasSize(2);
        assertThat(citations.get(0).citationType()).isEqualTo(CitationType.ARTICLE);
        assertThat(citations.get(1).citationType()).isEqualTo(CitationType.PAGE);
    }

    // ------------------------------------------------------------------ 조각으로 와도 같다 (#447)

    /*
     * **조각을 어떻게 잘라 넣어도 한 번에 넣은 것과 글자 하나까지 같다.**
     *
     * 스트리밍이 서 있는 자리가 여기다 — 토큰이 조각 경계에 걸쳐 오는 것이 예외가 아니라 보통이고
     * (`[`와 `1]`이 다른 조각에 실린다), 그때 판정이 어긋나면 화면에 반쪽짜리 대괄호가 남는다.
     * 한 글자씩 넣어 보는 것은 **가장 나쁜 자름**을 고른 것이다.
     */
    @Test
    void producesTheSameAnswerNoMatterHowTheDeltasAreCut() {
        List<RetrievedChunk> chunks =
                List.of(article(7, null, "제7조", "회원의 구분", "6항 정회원은 …"), page(12, "정산 기한은 …"));
        String answer = "정회원 승격은 총회의 동의가 필요합니다. [1] 지어낸 것은 [9] 입니다. 정산은 [2] 이고 [제7조] 는 쓰지 않습니다.";

        CitationVerifier.Verified atOnce = verifier.verify(answer, chunks);

        for (int size : new int[] {1, 2, 3, 7, 40}) {
            CitationVerifier.Session session = verifier.open(chunks);
            StringBuilder streamed = new StringBuilder();
            for (String piece : cut(answer, size)) {
                streamed.append(session.accept(piece));
            }
            streamed.append(session.finish());

            assertThat(streamed.toString()).as("조각 %d자".formatted(size)).isEqualTo(atOnce.answer());
            assertThat(session.verified().answer()).isEqualTo(atOnce.answer());
            assertThat(session.verified().responses())
                    .as("조각 %d자".formatted(size))
                    .isEqualTo(atOnce.responses());
            assertThat(session.verified().dropped()).isEqualTo(atOnce.dropped());
        }

        assertThat(atOnce.answer())
                .isEqualTo("정회원 승격은 총회의 동의가 필요합니다. [1] 지어낸 것은 입니다. 정산은 [2] 이고 는 쓰지 않습니다.");
        assertThat(atOnce.dropped()).isEqualTo(2);
    }

    /*
     * **닫히지 않은 대괄호는 판정이 끝날 때까지 나가지 않는다** — 버릴 토큰을 흘려보낸 뒤에
     * 되돌릴 방법이 없기 때문이다. 앞의 공백도 함께 붙들려 있어야 지운 자리에 «입니다 .»가
     * 남지 않는다.
     */
    @Test
    void holdsBackAnUnclosedBracketAndTheBlankBeforeIt() {
        CitationVerifier.Session session =
                verifier.open(List.of(article(7, null, "제7조", "회원의 구분", "6항 정회원은 …")));

        assertThat(session.accept("그렇습니다. ")).as("끝의 공백은 붙들어 둔다").isEqualTo("그렇습니다.");
        assertThat(session.accept("[9")).as("닫히지 않은 대괄호도 붙들어 둔다").isEmpty();
        assertThat(session.accept("] 끝.")).isEqualTo(" 끝.");
        assertThat(session.finish()).isEmpty();
        assertThat(session.verified().answer()).isEqualTo("그렇습니다. 끝.");
    }

    /*
     * ⚠️ **끝내 닫히지 않은 대괄호는 붙들고만 있지 않는다.** 모델이 `[`를 찍고 긴 문장을 이어 가면
     * 그 뒤가 전부 버퍼에 쌓여 «화면이 멈춘 것처럼» 보인다 — 인용 토큰이 될 수 없다는 것이
     * 확정되는 순간(줄이 바뀌거나 100자를 넘으면) 평범한 글자로 내보낸다.
     */
    @Test
    void stopsHoldingABracketThatCanNoLongerBecomeACitation() {
        CitationVerifier.Session session = verifier.open(List.of());

        assertThat(session.accept("목록: [가나다")).isEqualTo("목록:");
        assertThat(session.accept("\n다음 줄")).as("줄이 바뀌면 인용 토큰이 될 수 없다").isEqualTo(" [가나다\n다음 줄");
    }

    // ------------------------------------------------------------------ 픽스처

    private static List<String> cut(String text, int size) {
        List<String> pieces = new ArrayList<>();
        for (int at = 0; at < text.length(); at += size) {
            pieces.add(text.substring(at, Math.min(text.length(), at + size)));
        }
        return pieces;
    }

    private RetrievedChunk article(
            int number, Integer branch, String label, String title, String body) {

        return new RetrievedChunk(
                Document.builder()
                        .text("제2장 회원 · %s (%s)\n%s".formatted(label, title, body))
                        .metadata(articleMetadata(number, branch, label, title, false))
                        .score(0.8)
                        .build(),
                regulation);
    }

    private RetrievedChunk supplementaryArticle(
            int number, String label, String title, String body) {

        return new RetrievedChunk(
                Document.builder()
                        .text("부칙 %s (%s)\n%s".formatted(label, title, body))
                        .metadata(articleMetadata(number, null, label, title, true))
                        .score(0.8)
                        .build(),
                regulation);
    }

    private RetrievedChunk page(int page, String body) {
        return new RetrievedChunk(
                Document.builder()
                        .text("2026 지원금 집행 지침 · p.%d\n%s".formatted(page, body))
                        .metadata(genericMetadata(page))
                        .score(0.7)
                        .build(),
                guideline);
    }

    private Map<String, Object> articleMetadata(
            int number, Integer branch, String label, String title, boolean supplementary) {

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(RagChunkStore.RAG_DOCUMENT_ID_KEY, 1L);
        metadata.put(RagChunkMetadata.DOC_TYPE, RagDocumentType.STRUCTURED.name());
        metadata.put(RagChunkMetadata.APPLY_STATUS, RagApplyStatus.EFFECTIVE.name());
        metadata.put(RagChunkMetadata.CHAPTER, supplementary ? "부칙" : "제2장 회원");
        metadata.put(RagChunkMetadata.SUPPLEMENTARY, supplementary);
        metadata.put(RagChunkMetadata.ARTICLE_NUMBER, number);
        metadata.put(RagChunkMetadata.ARTICLE_LABEL, label);
        metadata.put(RagChunkMetadata.ARTICLE_TITLE, title);
        metadata.put(
                RagChunkMetadata.CITATION,
                (supplementary ? "부칙 " : "") + "%s (%s)".formatted(label, title));
        if (branch != null) {
            metadata.put(RagChunkMetadata.ARTICLE_BRANCH_NUMBER, branch);
        }
        return metadata;
    }

    private Map<String, Object> genericMetadata(Integer page) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(RagChunkStore.RAG_DOCUMENT_ID_KEY, 2L);
        metadata.put(RagChunkMetadata.DOC_TYPE, RagDocumentType.GENERIC.name());
        metadata.put(RagChunkMetadata.APPLY_STATUS, RagApplyStatus.EFFECTIVE.name());
        if (page != null) {
            metadata.put(RagChunkMetadata.PAGE, page);
        }
        return metadata;
    }
}
