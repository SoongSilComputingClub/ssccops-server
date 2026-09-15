package org.sscc.ssccopsserver.domain.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
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
 * 인용 검증 — **없는 조를 인용하는 순간 이 기능의 값이 사라진다** (#403 · 기획안 §6.3 · §14.2).
 *
 * 여기서 보는 것은 «모델이 무엇을 쓰든 코드가 대조한 것만 남는가»다. 모델을 부르지 않으므로
 * CI에서 매번 돈다 — 실제 모델 품질은 골든셋(#405)의 몫이고 이 층은 **모델이 규칙을 어겼을 때**
 * 무슨 일이 일어나는지를 못 박는다.
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

    /* 넣어 준 발췌를 가리키는 표기는 그대로 남고 인용 카드가 만들어진다 */
    @Test
    void keepsACitationThatPointsAtAnExcerptWeProvided() {
        List<RetrievedChunk> chunks = List.of(article(7, null, "제7조", "회원의 구분", "6항 정회원은 …"));

        CitationVerifier.Verified verified =
                verifier.verify("정회원 승격은 총회의 동의가 필요합니다. [제7조]", chunks);

        assertThat(verified.answer()).isEqualTo("정회원 승격은 총회의 동의가 필요합니다. [제7조]");
        assertThat(verified.dropped()).isZero();
        assertThat(verified.responses()).hasSize(1);

        AssistantCitationResponse citation = verified.responses().get(0);
        assertThat(citation.citationType()).isEqualTo(CitationType.ARTICLE);
        assertThat(citation.docTitle()).isEqualTo("SSCC 동아리 회칙");
        assertThat(citation.chapter()).isEqualTo("제2장 회원");
        assertThat(citation.supplementary()).isFalse();
        assertThat(citation.article()).isEqualTo("제7조 (회원의 구분)");
        assertThat(citation.page()).as("ARTICLE이면 page는 null이다 — 서버가 대체값을 만들지 않는다").isNull();
        assertThat(citation.snippet()).isEqualTo("6항 정회원은 …");
    }

    /*
     * **지어낸 조는 인용 목록에서 빠질 뿐 아니라 본문에서도 지워진다.**
     *
     * 목록에서만 빼면 답변 문장에 `[제99조]`가 남아 화면이 «근거가 있는 문장»으로 읽는다 —
     * 인용 카드가 없다는 것을 알아채는 사람은 없다.
     */
    @Test
    void dropsAFabricatedArticleFromTheAnswerItself() {
        List<RetrievedChunk> chunks = List.of(article(7, null, "제7조", "회원의 구분", "6항 정회원은 …"));

        CitationVerifier.Verified verified =
                verifier.verify("정회원 승격은 총회의 동의가 필요합니다. [제99조]", chunks);

        assertThat(verified.responses()).isEmpty();
        assertThat(verified.dropped()).isEqualTo(1);
        assertThat(verified.answer()).isEqualTo("정회원 승격은 총회의 동의가 필요합니다.");
    }

    /*
     * **부칙 여부가 인용 키의 일부다** — 부칙에서 조번호가 1로 리셋되므로 `제1조`만으로는 본칙의
     * 제1조(명칭)와 갈리지 않는다(§5.3).
     */
    @Test
    void tellsSupplementaryArticlesApartFromTheMainBody() {
        List<RetrievedChunk> chunks =
                List.of(
                        article(1, null, "제1조", "명칭", "본 회의 명칭은 …"),
                        supplementaryArticle(1, "제1조", "용어", "이 부칙에서 쓰는 용어는 …"));

        assertThat(verifier.verify("[제1조]", chunks).responses().get(0).supplementary()).isFalse();

        AssistantCitationResponse fromSupplement =
                verifier.verify("[부칙 제1조]", chunks).responses().get(0);
        assertThat(fromSupplement.supplementary()).isTrue();
        assertThat(fromSupplement.article()).isEqualTo("부칙 제1조 (용어)");
    }

    /* 가지 조번호는 별개의 조다 — `제27조`를 인용해도 `제27조의2` 청크가 그것을 받지 않는다 */
    @Test
    void treatsABranchArticleAsADifferentArticle() {
        List<RetrievedChunk> chunks = List.of(article(27, 2, "제27조의2", "개인정보의 보호", "개인정보는 …"));

        assertThat(verifier.verify("[제27조]", chunks).responses()).isEmpty();
        assertThat(verifier.verify("[제27조의2]", chunks).responses()).hasSize(1);
    }

    /*
     * **항은 그 청크 본문에 실제로 있을 때만 싣는다.** 긴 조는 항 묶음으로 갈리므로(#397) 모델이
     * 인용한 항이 같은 조의 다른 청크에 있을 수 있는데, 그때 조 인용은 맞고 항만 확인되지 않은
     * 것이라 **조까지 싣고 항은 비운다** — 맞는 인용을 버리지도, 확인하지 못한 값을 싣지도 않는다.
     */
    @Test
    void carriesTheClauseOnlyWhenTheExcerptActuallyHasIt() {
        List<RetrievedChunk> chunks = List.of(article(7, null, "제7조", "회원의 구분", "6항 정회원은 …"));

        assertThat(verifier.verify("[제7조 6항]", chunks).responses().get(0).clause()).isEqualTo("6항");
        assertThat(verifier.verify("[제7조 4항]", chunks).responses().get(0).clause())
                .as("4항은 다른 청크에 있다 — 조는 맞으므로 인용 자체는 살린다")
                .isNull();
    }

    /* `p.12`는 그 쪽에서 시작하는 평문 청크가 있을 때만 통과한다 */
    @Test
    void verifiesPageCitationsAgainstTheExcerptPage() {
        List<RetrievedChunk> chunks = List.of(page(12, "지원금은 활동 종료일로부터 30일 이내에 …"));

        AssistantCitationResponse citation = verifier.verify("[p.12]", chunks).responses().get(0);
        assertThat(citation.citationType()).isEqualTo(CitationType.PAGE);
        assertThat(citation.page()).isEqualTo(12);
        assertThat(citation.docTitle()).isEqualTo("2026 지원금 집행 지침");
        assertThat(citation.article()).as("PAGE면 조항 쪽 필드가 전부 null이다").isNull();
        assertThat(citation.chapter()).isNull();

        assertThat(verifier.verify("[p.99]", chunks).responses())
                .as("넣어 주지 않은 쪽은 인용이 될 수 없다")
                .isEmpty();
    }

    /*
     * **페이지가 없는 형식(DOCX)의 표기는 문서명이다**(#398 실측). 「전부 1쪽」을 지어내지 않으므로
     * 인용이 문서명까지만 가고, `page`가 `null`인 것이 **정상**이다.
     */
    @Test
    void acceptsTheDocumentNameWhenTheFormatHasNoPages() {
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
                verifier.verify("[학술국 운영 세칙]", List.of(chunk)).responses().get(0);

        assertThat(citation.citationType()).isEqualTo(CitationType.PAGE);
        assertThat(citation.page()).isNull();
        assertThat(citation.docTitle()).isEqualTo("학술국 운영 세칙");
    }

    /* 같은 근거를 두 번 인용해도 카드는 하나다 — 화면이 같은 조문을 두 번 그리지 않는다 */
    @Test
    void doesNotCarryTheSameEvidenceTwice() {
        List<RetrievedChunk> chunks = List.of(article(7, null, "제7조", "회원의 구분", "6항 정회원은 …"));

        assertThat(verifier.verify("[제7조] 그리고 [제7조]", chunks).responses()).hasSize(1);
    }

    /* 한 답변에 두 유형이 섞이는 것이 정상이다 — 회칙이 세부 규정을 위임하고 있다(§6.3) */
    @Test
    void mixesArticleAndPageCitationsInOneAnswer() {
        List<RetrievedChunk> chunks =
                List.of(article(27, null, "제27조", "세부 규정", "세부 규정은 …"), page(12, "정산 기한은 …"));

        List<AssistantCitationResponse> citations =
                verifier.verify("세부 규정은 지침을 따릅니다. [제27조] 정산은 30일 이내입니다. [p.12]", chunks)
                        .responses();

        assertThat(citations).hasSize(2);
        assertThat(citations.get(0).citationType()).isEqualTo(CitationType.ARTICLE);
        assertThat(citations.get(1).citationType()).isEqualTo(CitationType.PAGE);
    }

    /* 인용처럼 보이지 않는 대괄호는 건드리지 않는다 — 지우는 것은 조·페이지 모양의 토큰뿐이다 */
    @Test
    void leavesBracketsThatAreNotCitationsAlone() {
        CitationVerifier.Verified verified =
                verifier.verify("[참고] 규정 문서에서 근거를 찾지 못했습니다.", List.of());

        assertThat(verified.answer()).isEqualTo("[참고] 규정 문서에서 근거를 찾지 못했습니다.");
        assertThat(verified.dropped()).isZero();
    }

    /* 발췌를 하나도 넣어 주지 않았으면 어떤 인용도 통과하지 못한다 */
    @Test
    void verifiesNothingWithoutExcerpts() {
        CitationVerifier.Verified verified = verifier.verify("제7조에 따르면 그렇습니다. [제7조]", List.of());

        assertThat(verified.responses()).isEmpty();
        assertThat(verified.answer()).isEqualTo("제7조에 따르면 그렇습니다.");
    }

    // ------------------------------------------------------------------ 픽스처

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
