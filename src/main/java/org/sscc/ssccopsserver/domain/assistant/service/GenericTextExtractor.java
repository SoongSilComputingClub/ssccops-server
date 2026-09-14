package org.sscc.ssccopsserver.domain.assistant.service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;

import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.microsoft.ooxml.OOXMLParser;
import org.apache.tika.parser.pdf.PDFParser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.assistant.code.RagDocumentFormat;
import org.sscc.ssccopsserver.domain.assistant.code.RagDocumentType;
import org.sscc.ssccopsserver.domain.assistant.code.error.AssistantErrorCode;
import org.sscc.ssccopsserver.domain.assistant.dto.ExtractedDocument;
import org.sscc.ssccopsserver.domain.assistant.dto.ExtractedPage;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.extern.slf4j.Slf4j;

/*
 * 받아 온 PDF·DOCX를 **평문 + 페이지 경계**로 뽑는다 (#398 · 기획안 §5.4).
 *
 * ── 왜 PDFBox가 아니라 Tika인가 ─────────────────────────────
 *
 * **페이지를 주기 때문이다.** `GENERIC` 경로의 인용은 `[p.12]` 하나이므로 페이지 경계가 곧 이
 * 클래스의 산출물이고, PDFBox만 쓰면 DOCX에 두 번째 라이브러리가 붙는 데다 페이지 경계를 직접
 * 세야 한다. Tika는 PDF 쪽마다 `<div class="page">`를 열어 주고 그 신호를 `PageContentHandler`가 읽는다.
 *
 * ── 실측이 뒤집은 전제 하나 — DOCX에는 페이지가 없다 ─────────
 *
 * 기획안은 「Tika → 평문 + 페이지 경계」를 두 형식에 함께 적었는데 **DOCX에는 페이지 경계가
 * 아예 없다**(2026-09-14 실측: 명시적 페이지 나눔 `w:br w:type="page"`조차 줄바꿈 하나로 나오고
 * Tika의 OOXML 추출기에는 페이지를 여는 코드가 없다). 워드가 화면에 그릴 때 계산하는 값이라
 * 파일에 없는 것이므로 라이브러리를 바꿔도 답이 같다.
 *
 * 「전부 1쪽으로 적는다」를 택하지 않았다 — 9쪽의 문장에 `p.1`을 달면 운영진이 그 쪽을 열었을
 * 때 문장이 없고, **맞을 때도 틀릴 때도 있는 인용은 없는 인용보다 나쁘다**(§5.2 · 조항 인용을
 * 흉내 내지 않기로 한 것과 같은 줄기다). 그래서 DOCX 청크에는 `page`가 붙지 않고 인용이
 * 문서명까지만 간다.
 *
 * ── 파서를 확장자로 고정한다 — `AutoDetectParser`가 아니다 ──
 *
 * 우리가 받는 것은 `.pdf`·`.docx` 둘뿐이므로(`RagDocumentFormat`) 파서도 그 둘이면 된다.
 * 자동 감지는 클래스패스에 있는 모든 파서를 후보로 올리는데, 코퍼스에 올라오는 것은 **외부에서
 * 받아 온 파일**이라 실행될 수 있는 파서가 적을수록 좋다. 확장자와 내용이 어긋난 파일(`.pdf`로
 * 이름만 바꾼 zip)은 파서가 열지 못해 400이 되고, 그것이 맞는 결과다.
 *
 * ── OCR을 붙이지 않는다 ─────────────────────────────────────
 *
 * `PDFParserConfig`의 기본 OCR 전략은 `AUTO`라 tesseract가 깔린 환경에서는 조용히 OCR이 돈다.
 * **인식 오류가 규정 조문을 조용히 바꾸는데 그 답을 근거로 사람의 자격을 판단하므로** 명시적으로
 * 끈다 — 환경에 따라 코퍼스의 내용이 달라지는 것은 그 자체로 고장이다. 스캔본은 400으로 거절하고
 * 운영진이 텍스트 PDF를 구해 오는 것이 경로다.
 */
@Slf4j
@Component
public class GenericTextExtractor {

    /** 오류 문구에 본문을 싣지 않는다 — 예외 메시지·스택은 내부 구조가 응답으로 새는 길이다(`GeneralException.detail` 주석) */
    private static final String CORRUPTED = "파일을 열지 못했습니다. 다른 프로그램에서 열리는지 확인하고 다시 올려 주세요.";

    public ExtractedDocument extract(byte[] content, String fileName) {
        RagDocumentFormat format = RagDocumentFormat.fromFileName(fileName);
        if (format.getDocumentType() != RagDocumentType.GENERIC) {
            // `.md`는 구조화 문서다 — 평문으로 뽑으면 조 단위 인용을 잃는다
            throw new GeneralException(
                    AssistantErrorCode.RAG_DOCUMENT_PARSE_FAILED,
                    "`.md`는 회칙 계약으로 읽습니다. 평문 추출 대상은 `.pdf`·`.docx`입니다.");
        }

        PageContentHandler handler = new PageContentHandler();
        try (InputStream input = new ByteArrayInputStream(content)) {
            parserFor(format).parse(input, handler, new Metadata(), parseContext());
        } catch (EncryptedDocumentException exception) {
            throw new GeneralException(
                    AssistantErrorCode.RAG_DOCUMENT_PARSE_FAILED,
                    "암호가 걸린 문서입니다. 암호를 푼 파일로 다시 올려 주세요.");
        } catch (Exception exception) {
            log.warn("규정 문서 추출 실패 — format={}", format, exception);
            throw new GeneralException(AssistantErrorCode.RAG_DOCUMENT_PARSE_FAILED, CORRUPTED);
        }

        List<ExtractedPage> pages = handler.pages();
        ExtractedDocument extracted = new ExtractedDocument(pages);
        if (extracted.blank()) {
            /*
             * 스캔 이미지 PDF가 여기 걸린다. 「빈 문서를 통과시키고 청크 0개로 색인 완료」를 두지 않는
             * 것은 그것이 화면에 «색인 완료»로 뜨는데 무엇을 물어도 답하지 못하는 상태이고, 원인이
             * 파일에 있다는 신호가 아무 데도 남지 않기 때문이다.
             */
            throw new GeneralException(
                    AssistantErrorCode.RAG_DOCUMENT_PARSE_FAILED,
                    "텍스트가 한 글자도 추출되지 않았습니다. 스캔한 이미지 PDF는 받을 수 없으니 글자를 선택할 수 있는 파일로 올려 주세요.");
        }
        log.info(
                "규정 문서 추출 완료 — format={} 페이지={} 글자={}",
                format,
                pages.size(),
                pages.stream().mapToInt(page -> page.text().length()).sum());
        return extracted;
    }

    private static Parser parserFor(RagDocumentFormat format) {
        return format == RagDocumentFormat.PDF ? new PDFParser() : new OOXMLParser();
    }

    /*
     * 내장 문서를 재귀 파싱하지 않는다 — `ParseContext`에 `Parser`를 넣지 않으면 Tika가
     * `EmptyParser`를 쓰므로 DOCX 안에 박힌 엑셀·PDF는 열리지 않는다. 규정 문서의 본문이 아니고,
     * 열어 주는 것은 「업로드 하나가 여러 파서를 부르는」 경로를 만드는 일이다.
     */
    private static ParseContext parseContext() {
        ParseContext context = new ParseContext();
        PDFParserConfig config = new PDFParserConfig();
        config.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
        context.set(PDFParserConfig.class, config);
        return context;
    }
}
