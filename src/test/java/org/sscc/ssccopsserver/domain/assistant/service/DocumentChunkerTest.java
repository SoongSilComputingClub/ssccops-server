package org.sscc.ssccopsserver.domain.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.sscc.ssccopsserver.domain.assistant.code.RagApplyStatus;
import org.sscc.ssccopsserver.domain.assistant.code.RagDocumentType;
import org.sscc.ssccopsserver.domain.assistant.dto.ExtractedDocument;
import org.sscc.ssccopsserver.domain.assistant.dto.ExtractedPage;
import org.sscc.ssccopsserver.domain.assistant.dto.GenericChunk;
import org.sscc.ssccopsserver.domain.assistant.dto.RegulationDocument;

/*
 * 고정 길이 청킹 — «어디서 끊고 무엇을 물고 가며 어느 쪽을 다는가» (#398 · 기획안 §5.4).
 *
 * 실제 문서로 세는 쪽은 `GenericGoldenSetTest`가 본다. 여기서는 최소한의 입력으로 규칙을 고정한다.
 */
class DocumentChunkerTest {

    private final DocumentChunker chunker = new DocumentChunker(new RegulationChunker());

    /** 짧은 문서는 한 청크이고 헤더에 쪽이 붙는다 */
    @Test
    void keepsShortDocumentAsOneChunk() {
        List<GenericChunk> chunks = chunk(page(1, "지원금은 활동 종료일로부터 30일 이내에 정산한다."));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).sequence()).isZero();
        assertThat(chunks.get(0).page()).isEqualTo(1);
        assertThat(chunks.get(0).citation()).isEqualTo("p.1");
        assertThat(chunks.get(0).text())
                .isEqualTo("2026 지원금 집행 지침 · p.1\n지원금은 활동 종료일로부터 30일 이내에 정산한다.");
    }

    /** 목표를 넘으면 **문단 경계에서** 끊는다 — 문단은 어느 청크에서도 반 토막이 나지 않는다 */
    @Test
    void breaksAtParagraphBoundaries() {
        List<String> paragraphs =
                IntStream.rangeClosed(1, 12).mapToObj(index -> paragraph(index, 90)).toList();
        List<GenericChunk> chunks = chunk(page(1, String.join("\n", paragraphs)));

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks)
                .as("overlap을 뺀 새 내용이 목표를 넘지 않는다")
                .allSatisfy(
                        chunk ->
                                assertThat(chunk.body().length())
                                        .isLessThanOrEqualTo(
                                                DocumentChunker.TARGET_CHUNK_CHARS
                                                        + DocumentChunker.OVERLAP_CHARS));
        assertThat(chunks)
                .as("문단이 잘리지 않는다 — 어느 청크에도 문단의 앞이나 뒤가 온전히 들어 있다")
                .allSatisfy(
                        chunk ->
                                assertThat(paragraphs)
                                        .anySatisfy(
                                                paragraph ->
                                                        assertThat(chunk.body())
                                                                .contains(paragraph)));
        assertThat(chunks.stream().map(GenericChunk::body).collect(Collectors.joining("\n")))
                .as("어느 문단도 사라지지 않는다")
                .contains(paragraphs.get(0))
                .contains(paragraphs.get(11));
    }

    /** 인접한 청크는 앞 청크의 꼬리를 물고 시작한다 — 경계에 걸린 문장이 어느 쪽에도 온전히 없는 것을 막는다 */
    @Test
    void adjacentChunksShareTheirBoundary() {
        List<GenericChunk> chunks =
                chunk(
                        page(
                                1,
                                IntStream.rangeClosed(1, 12)
                                        .mapToObj(index -> paragraph(index, 90))
                                        .collect(Collectors.joining("\n"))));

        for (int index = 1; index < chunks.size(); index++) {
            String previous = chunks.get(index - 1).body();
            String overlap = sharedPrefix(previous, chunks.get(index).body());
            assertThat(overlap.length())
                    .as("%d번 청크가 물고 온 꼬리", index)
                    .isBetween(1, DocumentChunker.OVERLAP_CHARS);
            assertThat(previous).endsWith(overlap);
        }
    }

    /** 문단 하나가 혼자 목표보다 길면 문장 경계에서 쪼갠다 — 그러지 않으면 청크 하나가 통째로 커진다 */
    @Test
    void splitsAParagraphThatIsLongerThanTheTargetOnItsOwn() {
        String sentence = "이 문장은 한 문단 안에서 계속 이어지며 길이를 채우기 위해 같은 말을 되풀이한다. ";
        String single = sentence.repeat(30).strip();

        List<GenericChunk> chunks = chunk(page(1, single));

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks)
                .allSatisfy(
                        chunk ->
                                assertThat(chunk.body().length())
                                        .isLessThanOrEqualTo(
                                                DocumentChunker.TARGET_CHUNK_CHARS
                                                        + DocumentChunker.OVERLAP_CHARS));
        assertThat(chunks.get(0).body()).endsWith("되풀이한다.");
    }

    /**
     * 쪽을 넘는 청크는 <b>시작 쪽</b>을 단다.
     *
     * <p>두 쪽을 다 적으면 인용이 길어지는데 운영진이 확인하러 여는 것은 시작 쪽 하나다. 넘어온 overlap이 쪽을 정하게 두지 않는 것도 같은 자리다 — 그러면 앞
     * 청크와 같은 쪽을 가리키면서 내용은 대부분 다음 쪽인 청크가 생긴다.
     */
    @Test
    void chunkThatCrossesAPageCarriesTheStartingPage() {
        List<GenericChunk> chunks =
                chunk(
                        page(7, paragraph(1, 500)),
                        page(8, paragraph(2, 500)),
                        page(9, paragraph(3, 200)));

        assertThat(chunks).extracting(GenericChunk::page).isSorted();
        assertThat(chunks.get(0).page()).isEqualTo(7);
        assertThat(chunks)
                .as("어느 청크도 자기 시작 쪽보다 앞선 쪽을 가리키지 않는다")
                .allSatisfy(chunk -> assertThat(chunk.page()).isBetween(7, 9));
        assertThat(chunks.stream().filter(chunk -> chunk.body().contains(paragraph(2, 500))))
                .as("8쪽의 문단을 처음 담는 청크는 7쪽 또는 8쪽에서 시작한다")
                .isNotEmpty();
    }

    /** 쪽이 없는 형식(DOCX)은 인용에 쪽을 싣지 않는다 — 「1쪽」을 지어내지 않는다 */
    @Test
    void documentWithoutPagesCarriesNoPageAtAll() {
        List<GenericChunk> chunks =
                chunk(new ExtractedPage(null, paragraph(1, 400) + "\n" + paragraph(2, 400)));

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).extracting(GenericChunk::page).containsOnlyNulls();
        assertThat(chunks.get(0).citation()).isNull();
        assertThat(chunks.get(0).heading()).isEqualTo("2026 지원금 집행 지침");
        assertThat(chunks.get(0).toDocument(7L, RagApplyStatus.EFFECTIVE).getMetadata())
                .as("값이 null인 key는 아예 넣지 않는다 — jsonb의 null은 필터에서 «있음»으로 세어진다")
                .doesNotContainKey(RagChunkMetadata.PAGE);
    }

    /** 판본의 값은 청커가 모른다 — `toDocument`를 부르는 쪽(#400)이 찍는다 */
    @Test
    void documentCarriesVersionMetadata() {
        Document document =
                chunk(page(12, "지원금은 활동 종료일로부터 30일 이내에 정산한다."))
                        .get(0)
                        .toDocument(42L, RagApplyStatus.DRAFT);

        assertThat(document.getText()).startsWith("2026 지원금 집행 지침 · p.12\n");
        assertThat(document.getMetadata())
                .containsEntry(RagChunkStore.RAG_DOCUMENT_ID_KEY, 42L)
                .containsEntry(RagChunkMetadata.DOC_TYPE, RagDocumentType.GENERIC.name())
                .containsEntry(RagChunkMetadata.APPLY_STATUS, RagApplyStatus.DRAFT.name())
                .containsEntry(RagChunkMetadata.SEQUENCE, 0)
                .containsEntry(RagChunkMetadata.PAGE, 12);
    }

    /**
     * 두 파싱 결과가 같은 `Document[]`로 나간다.
     *
     * <p>색인 워커(#400)가 갈래마다 다르게 조립하면 `toDocument`를 부르는 자리가 둘이 되고, 하나가 메타를 빠뜨리면 그 청크는 `ragDocId`가 없어
     * <b>아무도 지울 수 없는 고아</b>가 된다.
     */
    @Test
    void bothParseResultsLeaveAsTheSameDocuments() {
        RegulationDocument parsed =
                new RegulationParser()
                        .parse(
                                """
                                ## 제1장 총칙

                                ### 제1조 (명칭)

                                본 회의 명칭은 숭실 컴퓨팅 클럽이라 한다.
                                """);

        List<Document> structured = chunker.documents(parsed, 9L, RagApplyStatus.EFFECTIVE);
        List<Document> generic =
                chunker.documents(
                        new ExtractedDocument(List.of(page(3, "지원금 한도는 30만 원이다."))),
                        "2026 지원금 집행 지침",
                        9L,
                        RagApplyStatus.EFFECTIVE);

        assertThat(structured).hasSize(1);
        assertThat(generic).hasSize(1);
        assertThat(structured.get(0).getMetadata())
                .containsEntry(RagChunkStore.RAG_DOCUMENT_ID_KEY, 9L)
                .containsEntry(RagChunkMetadata.DOC_TYPE, RagDocumentType.STRUCTURED.name());
        assertThat(generic.get(0).getMetadata())
                .containsEntry(RagChunkStore.RAG_DOCUMENT_ID_KEY, 9L)
                .containsEntry(RagChunkMetadata.DOC_TYPE, RagDocumentType.GENERIC.name());
    }

    private List<GenericChunk> chunk(ExtractedPage... pages) {
        return chunker.chunk(new ExtractedDocument(List.of(pages)), "2026 지원금 집행 지침");
    }

    private static ExtractedPage page(int number, String text) {
        return new ExtractedPage(number, text);
    }

    /** 길이를 맞춘 한 문단. 번호를 앞에 달아 어느 문단이 어디로 갔는지 볼 수 있게 한다 */
    private static String paragraph(int index, int length) {
        String head = index + "항 ";
        return head + "가나다라마바사아자차".repeat(length / 10).substring(0, length - head.length());
    }

    private static String sharedPrefix(String previous, String next) {
        for (int length = Math.min(previous.length(), next.length()); length > 0; length--) {
            if (previous.endsWith(next.substring(0, length))) {
                return next.substring(0, length);
            }
        }
        return "";
    }
}
