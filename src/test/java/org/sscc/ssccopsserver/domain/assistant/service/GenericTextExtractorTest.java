package org.sscc.ssccopsserver.domain.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.sscc.ssccopsserver.domain.assistant.code.error.AssistantErrorCode;
import org.sscc.ssccopsserver.domain.assistant.dto.ExtractedDocument;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

/*
 * Tika 추출 — **페이지가 나오는가, 그리고 나오지 않을 때 무엇을 하는가** (#398 · 기획안 §5.4 · §14.1).
 *
 * 픽스처는 셋이며 전부 `src/test/resources/rag/`에 있다.
 *
 * - `regulation-current.pdf` — 현행 회칙 6쪽. `private-workspace/`의 원본에서 **문서 정보·XMP를
 *   지운** 사본이다(이 레포가 공개라 작성자 이름을 싣지 않는다 · 본문과 쪽 나눔은 원본 그대로).
 * - `guideline-sample.docx` — 지어낸 세칙. 개인정보가 없고 4KB다.
 * - `scanned-no-text.pdf` — 이미지만 있고 글자가 없는 2쪽 PDF. 스캔본이 걸리는 자리를 흉내 낸다.
 *
 * **이 코퍼스를 올리는 것이 아니다.** 개정안과 내용이 어긋나 근거가 흐려지므로 현행 회칙은
 * 코퍼스에 넣지 않기로 했고(ssccops#323), 여기서는 «PDF에서 쪽이 나오는가»의 증인으로만 쓴다.
 */
class GenericTextExtractorTest {

    private final GenericTextExtractor extractor = new GenericTextExtractor();

    /** PDF는 쪽마다 갈린다 — 이 값이 곧 인용의 `p.12`다 */
    @Test
    void pdfKeepsPageBoundaries() {
        ExtractedDocument extracted = extract("regulation-current.pdf");

        assertThat(extracted.paginated()).isTrue();
        assertThat(extracted.pages()).as("원본이 6쪽이다").hasSize(6);
        assertThat(extracted.pages()).extracting("number").containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(extracted.blank()).isFalse();

        assertThat(extracted.pages().get(0).text()).contains("제 1장 총칙").contains("숭실 컴퓨팅 클럽");
        assertThat(extracted.pages().get(4).text())
                .as("제27조는 5쪽에 있다 — 쪽 번호가 밀리면 여기가 먼저 틀린다")
                .contains("제27조 (복지)");
        assertThat(extracted.pages().get(5).text()).contains("효력을 발생한다");
    }

    /** 한 문단 안의 줄바꿈은 접히고 문단 사이만 남는다 — PDF는 줄 끝에서 단어를 끊는다 */
    @Test
    void foldsLineBreaksInsideAParagraph() {
        String page = extract("regulation-current.pdf").pages().get(0).text();

        assertThat(page).doesNotContain("  ").doesNotContain("\n\n");
        assertThat(page.lines()).allSatisfy(line -> assertThat(line).isEqualTo(line.strip()));
    }

    /**
     * <b>DOCX에는 페이지가 없다</b> — 워드가 그릴 때 계산하는 값이라 파일에 경계가 없고 Tika도 주지 않는다.
     *
     * <p>「전부 1쪽」으로 채우지 않는 것이 이 테스트가 지키는 결정이다. 9쪽의 문장에 `p.1`을 달면 운영진이 그 쪽을 열었을 때 문장이 없다.
     */
    @Test
    void docxHasNoPages() {
        ExtractedDocument extracted = extract("guideline-sample.docx");

        assertThat(extracted.paginated()).isFalse();
        assertThat(extracted.pages()).hasSize(1);
        assertThat(extracted.pages().get(0).number()).isNull();
        assertThat(extracted.pages().get(0).text()).contains("지원금은 활동 종료일로부터 삼십 일 이내에 정산");
    }

    /** 스캔 이미지 PDF는 400이다 — OCR을 붙이지 않기로 했으므로 여기서 끝난다 */
    @Test
    void rejectsDocumentWithoutAnyText() {
        byte[] scanned = read("scanned-no-text.pdf");

        assertThatThrownBy(() -> extractor.extract(scanned, "scanned-no-text.pdf"))
                .isInstanceOf(GeneralException.class)
                .hasFieldOrPropertyWithValue(
                        "errorCode", AssistantErrorCode.RAG_DOCUMENT_PARSE_FAILED)
                .hasMessageContaining("텍스트가 한 글자도");
    }

    /** `.md`는 회칙 계약(`RegulationParser`)이 읽는다 — 평문으로 뽑으면 조 단위 인용을 잃는다 */
    @Test
    void refusesStructuredDocuments() {
        byte[] markdown = "## 제1장 총칙\n\n### 제1조 (명칭)\n".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> extractor.extract(markdown, "회칙.md"))
                .isInstanceOf(GeneralException.class)
                .hasMessageContaining("`.md`");
    }

    /**
     * 확장자와 내용이 어긋난 파일은 파서가 열지 못해 400이다.
     *
     * <p>`AutoDetectParser`를 쓰지 않기로 한 결정이 여기서 보인다 — 자동 감지라면 이 파일이 어떤 파서로든 열렸을 것이고, 코퍼스에 올라오는 것은
     * 외부에서 받아 온 파일이라 실행될 수 있는 파서가 적을수록 좋다.
     */
    @Test
    void rejectsFileThatIsNotWhatItsNameSays() {
        byte[] notAPdf = "이것은 PDF가 아니라 그냥 글이다.".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> extractor.extract(notAPdf, "학칙.pdf"))
                .isInstanceOf(GeneralException.class)
                .hasFieldOrPropertyWithValue(
                        "errorCode", AssistantErrorCode.RAG_DOCUMENT_PARSE_FAILED)
                .as("예외 메시지·스택을 응답으로 흘리지 않는다")
                .hasMessageContaining("파일을 열지 못했습니다");
    }

    private ExtractedDocument extract(String fixture) {
        return extractor.extract(read(fixture), fixture);
    }

    private static byte[] read(String fixture) {
        try (InputStream stream =
                GenericTextExtractorTest.class.getResourceAsStream("/rag/" + fixture)) {
            if (stream == null) {
                throw new IllegalStateException("픽스처가 없다: " + fixture);
            }
            return stream.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
