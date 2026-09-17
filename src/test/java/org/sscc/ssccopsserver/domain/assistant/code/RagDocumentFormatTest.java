package org.sscc.ssccopsserver.domain.assistant.code;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.sscc.ssccopsserver.domain.assistant.code.error.AssistantErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

/*
 * 확장자 표 — **파서가 여기서 갈린다** (#398 · #445 · 기획안 §5.2).
 *
 * **유형(`RagDocumentType`)은 더 이상 여기서 갈리지 않는다** (#445). 그것은 파싱 결과가 정하며
 * (`RagDocumentServiceImpl.resolveType`) 이 표가 주는 것은 «어떻게 열어 보는가»와 «회칙 파서를
 * 먼저 시도할 만한가» 둘이다. 요청이 유형을 신고하지 않는다는 원칙(#210)은 그대로다.
 */
class RagDocumentFormatTest {

    /*
     * **`.md`만 회칙 후보다.** 이 값이 곧 «회칙 파서를 시도한다»이고, 실패는 400이 아니라 평문
     * 경로로의 이동이다 — 그 판단은 서비스가 한다.
     */
    @Test
    void onlyMarkdownIsARegulationCandidate() {
        assertThat(RagDocumentFormat.fromFileName("회칙개정_2026_개정안전문.md"))
                .isEqualTo(RagDocumentFormat.MARKDOWN);
        assertThat(RagDocumentFormat.MARKDOWN.isRegulationCandidate()).isTrue();

        for (RagDocumentFormat format : RagDocumentFormat.values()) {
            if (format != RagDocumentFormat.MARKDOWN) {
                assertThat(format.isRegulationCandidate())
                        .as("%s는 회칙 파서를 시도하지 않는다", format)
                        .isFalse();
            }
        }
    }

    /*
     * 확장자마다 파서가 고정이다 — `AutoDetectParser`를 쓰지 않는 결정이 이 표에 있다(#398).
     *
     * **#445에서 확장자가 늘었지만 파서는 셋뿐이다**: 새로 받는 OOXML 둘은 `.docx`와 같은
     * 파서이고, OLE2 셋만 하나를 더한다. 평문은 파서를 쓰지 않는다.
     */
    @Test
    void extensionPicksTheParser() {
        assertThat(RagDocumentFormat.fromFileName("메모.txt").getExtraction())
                .isEqualTo(RagDocumentFormat.Extraction.PLAIN);
        assertThat(RagDocumentFormat.MARKDOWN.getExtraction())
                .isEqualTo(RagDocumentFormat.Extraction.PLAIN);

        assertThat(RagDocumentFormat.fromFileName("숭실대 학칙 발췌.pdf").getExtraction())
                .as("쪽 경계를 주는 유일한 형식")
                .isEqualTo(RagDocumentFormat.Extraction.PDF);

        for (String ooxml : new String[] {"세칙.docx", "발표.pptx", "명부.xlsx"}) {
            assertThat(RagDocumentFormat.fromFileName(ooxml).getExtraction())
                    .as("%s는 같은 OOXML 파서다", ooxml)
                    .isEqualTo(RagDocumentFormat.Extraction.OOXML);
        }
        for (String ole2 : new String[] {"옛세칙.doc", "옛발표.ppt", "옛명부.xls"}) {
            assertThat(RagDocumentFormat.fromFileName(ole2).getExtraction())
                    .as("%s는 OLE2다", ole2)
                    .isEqualTo(RagDocumentFormat.Extraction.OLE2);
        }
    }

    /** 대소문자는 가르지 않는다 — 운영진이 받아 오는 파일에 `.PDF`가 섞인다 */
    @Test
    void ignoresCase() {
        assertThat(RagDocumentFormat.fromFileName("GUIDELINE.PDF"))
                .isEqualTo(RagDocumentFormat.PDF);
        assertThat(RagDocumentFormat.fromFileName("Rules.DocX")).isEqualTo(RagDocumentFormat.DOCX);
    }

    /*
     * 모르는 확장자는 400 `RAG_DOCUMENT_UNSUPPORTED_TYPE`이고 사유가 «무엇을 받는가»를 말한다.
     *
     * **파싱 실패와 코드가 갈린다** (#399) — 운영진이 할 일이 한쪽은 «형식을 바꾼다»이고 다른
     * 쪽은 «글자를 선택할 수 있는 파일로 다시 올린다»라 겹치지 않는다. 그 안에서 확장자마다
     * 코드를 나누지는 않는다(#150).
     *
     * `.hwp`가 여기 남는 것은 **의도한 것이다** — Tika에 한글 파서가 없어 모듈 추가로 되지 않고,
     * 외부 라이브러리 판단이 필요해 #445에서 뗐다.
     */
    @Test
    void rejectsUnknownExtension() {
        assertThatThrownBy(() -> RagDocumentFormat.fromFileName("학칙.hwp"))
                .isInstanceOf(GeneralException.class)
                .hasFieldOrPropertyWithValue(
                        "errorCode", AssistantErrorCode.RAG_DOCUMENT_UNSUPPORTED_TYPE)
                .hasMessageContaining(".pdf")
                .hasMessageContaining("학칙.hwp");

        assertThatThrownBy(() -> RagDocumentFormat.fromFileName(null))
                .isInstanceOf(GeneralException.class);
        assertThatThrownBy(() -> RagDocumentFormat.fromFileName("확장자없는파일"))
                .isInstanceOf(GeneralException.class);
    }

    /*
     * **오류 문구가 표를 그대로 읽는다.** 확장자를 더하면서 문구를 따로 고치지 않아도 되며,
     * 반대로 문구만 고치고 표를 빠뜨리는 일도 생기지 않는다.
     */
    @Test
    void errorMessageListsEveryAcceptedExtension() {
        assertThatThrownBy(() -> RagDocumentFormat.fromFileName("학칙.hwp"))
                .satisfies(
                        thrown -> {
                            for (RagDocumentFormat format : RagDocumentFormat.values()) {
                                assertThat(thrown.getMessage())
                                        .as("%s가 문구에 있어야 한다", format.getExtension())
                                        .contains(format.getExtension());
                            }
                        });
    }

    /** 마지막 확장자가 이긴다 — `회칙.md.txt`는 실제로 `.txt` 파일이다 */
    @Test
    void theLastExtensionWins() {
        assertThat(RagDocumentFormat.fromFileName("회칙.md.txt")).isEqualTo(RagDocumentFormat.TEXT);
        assertThat(RagDocumentFormat.fromFileName("보고서.txt.docx"))
                .isEqualTo(RagDocumentFormat.DOCX);
    }
}
