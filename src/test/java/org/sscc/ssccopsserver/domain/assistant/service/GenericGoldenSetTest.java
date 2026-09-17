package org.sscc.ssccopsserver.domain.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.sscc.ssccopsserver.domain.assistant.dto.ExtractedDocument;
import org.sscc.ssccopsserver.domain.assistant.dto.GenericChunk;

/*
 * `GENERIC` 골든셋 — **실제 PDF 한 벌을 그대로 돌린다** (#398 · 기획안 §14.1).
 *
 * 리소스 `rag/regulation-current.pdf`는 `private-workspace/2026년도_학술분과_SSCC_동아리회칙.pdf`의
 * 사본이며 **문서 정보·XMP를 지웠다**(이 레포가 공개라 작성자 이름을 싣지 않는다 · 본문과 쪽
 * 나눔은 원본 그대로다). 파일명이 ASCII인 것은 `RegulationGoldenSetTest`와 같은 이유다 — CI
 * 러너의 로캘이 C면 한글 파일명이 클래스패스 조회에서 깨진다.
 *
 * **이 문서를 코퍼스에 올리는 것이 아니다**(ssccops#323 — 개정안과 내용이 어긋나 근거가 흐려진다).
 * 여기서는 «PDF에서 쪽이 나오고, 그 쪽이 청크를 따라 옳게 실리는가»의 증인으로만 쓴다. 실제
 * 학칙 발췌·세칙·지침이 들어오는 것은 서버 작업이 아니라 업로드이므로, 이 경로가 서 있으면 그날 바로 된다.
 *
 * **외부 호출이 없어 CI에서 매번 돈다.** 여기 있는 숫자는 문서를 세어 본 값이며(6쪽 · 제27조가
 * 5쪽) 청크 수는 600·100으로 자른 결과다 — **그 값을 움직이면 이 숫자가 먼저 틀리는 것이 이
 * 클래스가 하는 일이다.**
 */
class GenericGoldenSetTest {

    private static final String RESOURCE = "/rag/regulation-current.pdf";
    private static final String DOCUMENT_NAME = "2026년도 학술분과 SSCC 동아리회칙";

    private final GenericTextExtractor extractor = new GenericTextExtractor();
    private final DocumentChunker chunker = new DocumentChunker(new RegulationChunker());

    private final ExtractedDocument extracted = extractor.extract(read(), "regulation-current.pdf");
    private final List<GenericChunk> chunks = chunker.chunk(extracted, DOCUMENT_NAME);

    @Test
    void extractsEveryPageOfTheDocument() {
        assertThat(extracted.paginated()).isTrue();
        assertThat(extracted.pages()).hasSize(6);
        assertThat(extracted.pages()).noneMatch(page -> page.text().isBlank());
        assertThat(extracted.pages().stream().mapToInt(page -> page.text().length()).sum())
                .as("6쪽에서 6천 자 남짓 — 자릿수가 달라지면 추출이 끊긴 것이다")
                .isBetween(5_000, 7_000);
    }

    @Test
    void cutsTheDocumentIntoElevenChunks() {
        assertThat(chunks).as("6,093자를 600자·overlap 100자로 자른 결과").hasSize(11);
        assertThat(chunks).extracting(GenericChunk::sequence).isSorted();
        assertThat(chunks)
                .allSatisfy(
                        chunk ->
                                assertThat(chunk.body().length())
                                        .isLessThanOrEqualTo(
                                                DocumentChunker.TARGET_CHUNK_CHARS
                                                        + DocumentChunker.OVERLAP_CHARS));
    }

    /** 쪽 번호가 청크를 따라 옳게 실린다 — **인용의 `p.12`가 이 값이다** */
    @Test
    void everyChunkCarriesThePageItStartsOn() {
        assertThat(chunks)
                .extracting(GenericChunk::page)
                .containsExactly(1, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5);
        assertThat(chunks.get(0).heading()).isEqualTo(DOCUMENT_NAME + " · p.1");
        assertThat(chunks.get(0).text()).startsWith(DOCUMENT_NAME + " · p.1\n");
    }

    /**
     * 쪽을 넘는 청크는 시작 쪽만 단다 — <b>마지막 청크가 5쪽에서 시작해 6쪽을 삼킨다.</b>
     *
     * <p>그래서 이 문서에는 `p.6`을 가리키는 청크가 없다. 6쪽은 개정 이력 몇 줄뿐이라 혼자 청크가 되지 않는다.
     */
    @Test
    void theLastChunkStartsOnPageFiveAndSwallowsPageSix() {
        GenericChunk last = chunks.get(chunks.size() - 1);

        assertThat(last.page()).isEqualTo(5);
        assertThat(last.citation()).isEqualTo("p.5");
        assertThat(last.body()).contains("효력을 발생한다");
        assertThat(chunks).extracting(GenericChunk::page).doesNotContain(6);
    }

    /** 인접한 청크가 경계를 공유한다 — 조 하나가 두 청크 사이에서 반 토막 나지 않게 하는 값이다 */
    @Test
    void adjacentChunksShareAboutAHundredCharacters() {
        for (int index = 1; index < chunks.size(); index++) {
            String previous = chunks.get(index - 1).body();
            String overlap = sharedPrefix(previous, chunks.get(index).body());

            assertThat(overlap.length())
                    .as("%d번 청크가 물고 온 꼬리", index)
                    .isBetween(50, DocumentChunker.OVERLAP_CHARS);
        }
    }

    /** 본문이 빠짐없이 실린다 — 문단이 청크 사이에서 사라지지 않는다 */
    @Test
    void keepsTheWholeDocument() {
        String joined = String.join("\n", chunks.stream().map(GenericChunk::body).toList());

        assertThat(joined).contains("본 회의 명칭은 숭실 컴퓨팅 클럽");
        assertThat(joined).contains("제27조 (복지)");
        assertThat(joined).contains("효력을 발생한다");
        for (String page : extracted.pages().stream().map(page -> page.text()).toList()) {
            assertThat(joined).contains(page.lines().toList().get(1));
        }
    }

    private static String sharedPrefix(String previous, String next) {
        for (int length = Math.min(previous.length(), next.length()); length > 0; length--) {
            if (previous.endsWith(next.substring(0, length))) {
                return next.substring(0, length);
            }
        }
        return "";
    }

    private static byte[] read() {
        try (InputStream stream = GenericGoldenSetTest.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("골든셋 픽스처가 없다: " + RESOURCE);
            }
            return stream.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
