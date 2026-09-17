package org.sscc.ssccopsserver.domain.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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

    /*
     * `.md`·`.txt`는 **파서 없이** 읽는다 (#445).
     *
     * 그전에는 `.md`가 여기서 400이었다 — 확장자가 유형을 단정했으므로 «구조화 문서를 평문으로
     * 뽑는 것»이 곧 실수였기 때문이다. 이제 회칙 계약을 어긴 `.md`가 정상적으로 이 길로 온다.
     *
     * 쪽 번호가 없으므로 한 장이고 `number`가 `null`이다 — DOCX와 같은 모양이라 인용이 문서명
     * 까지만 간다(`ExtractedDocument.paginated()`가 그 값을 본다).
     */
    @Test
    void readsPlainTextWithoutAParser() {
        byte[] markdown =
                "## A. 기계적 수정 — 논의 없이 고칠 것\n\n- 오탈자 정리\n".getBytes(StandardCharsets.UTF_8);

        ExtractedDocument extracted = extractor.extract(markdown, "회칙개정_2026_검토목록.md");

        assertThat(extracted.pages()).hasSize(1);
        assertThat(extracted.paginated()).as("평문에는 쪽 경계가 없다").isFalse();
        assertThat(extracted.pages().get(0).text()).contains("기계적 수정").contains("오탈자 정리");

        assertThat(extractor.extract("회의록 메모".getBytes(StandardCharsets.UTF_8), "메모.txt").pages())
                .hasSize(1);
    }

    /*
     * BOM을 뗀다 — `RegulationParser`의 계약과 같다(#400).
     *
     * 같은 `.md`를 두 파서가 다르게 읽으면, 회칙 파서는 통과했는데 평문 경로에서만 첫 글자가
     * 달라지는 자리가 생긴다.
     */
    @Test
    void stripsByteOrderMarkFromPlainText() {
        byte[] withBom = "\uFEFF첫 글자".getBytes(StandardCharsets.UTF_8);

        assertThat(extractor.extract(withBom, "메모.txt").pages().get(0).text()).startsWith("첫 글자");
    }

    /** 빈 평문도 통과시키지 않는다 — 「색인 완료인데 무엇을 물어도 답하지 못하는」 상태를 만들지 않는다 */
    @Test
    void refusesBlankPlainText() {
        assertThatThrownBy(
                        () -> extractor.extract("   \n\n".getBytes(StandardCharsets.UTF_8), "빈.md"))
                .isInstanceOf(GeneralException.class)
                .hasFieldOrPropertyWithValue(
                        "errorCode", AssistantErrorCode.RAG_DOCUMENT_PARSE_FAILED)
                .hasMessageContaining("텍스트가 한 글자도");
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

    /*
     * ══ #445에서 늘어난 확장자가 실제로 열리는지 재 본다 ═══════════
     *
     * **파일을 여기서 만든다.** 리소스로 넣어 두면 «어떻게 만든 파일인가»가 사라져, 열리지 않을
     * 때 파서 문제인지 픽스처 문제인지 가릴 수 없다. POI는 Tika의 OOXML 모듈이 이미 싣고 있다.
     *
     * 확인하려는 것은 추출 품질이 아니라 **파서가 붙어 있는가**다 — 셀·슬라이드가 어떤 순서로
     * 이어지는지는 Tika의 몫이고, 그 글자가 나오기만 하면 청커·임베딩은 그 뒤를 알아서 한다.
     */
    @Test
    void opensTheOoxmlFormatsAddedIn445() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            workbook.createSheet("명부").createRow(0).createCell(0).setCellValue("정회원 승격 명단");
            workbook.write(bytes);

            assertThat(extractor.extract(bytes.toByteArray(), "명부.xlsx").pages().get(0).text())
                    .contains("정회원 승격 명단");
        }

        try (XMLSlideShow slides = new XMLSlideShow();
                ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            XSLFTextBox box = slides.createSlide().createTextBox();
            box.setText("2026 개강총회 안건");
            slides.write(bytes);

            assertThat(extractor.extract(bytes.toByteArray(), "총회.pptx").pages().get(0).text())
                    .contains("2026 개강총회 안건");
        }
    }

    /** 옛 OLE2는 파서가 다르다(`OfficeParser`) — 붙어 있지 않으면 «파일을 열지 못했습니다»가 된다 */
    @Test
    void opensLegacyOle2FormatsAddedIn445() throws Exception {
        try (HSSFWorkbook workbook = new HSSFWorkbook();
                ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            workbook.createSheet("명부").createRow(0).createCell(0).setCellValue("옛 명부 항목");
            workbook.write(bytes);

            assertThat(extractor.extract(bytes.toByteArray(), "옛명부.xls").pages().get(0).text())
                    .contains("옛 명부 항목");
        }
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
