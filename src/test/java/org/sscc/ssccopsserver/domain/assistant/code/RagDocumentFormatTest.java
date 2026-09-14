package org.sscc.ssccopsserver.domain.assistant.code;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.sscc.ssccopsserver.domain.assistant.code.error.AssistantErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

/*
 * 확장자 표 — **유형도 파서도 여기서 갈린다** (#398 · 기획안 §5.2).
 *
 * 요청이 유형을 신고하지 않으므로(#210) 이 표가 틀리면 회칙이 평문으로 잘려 조항 인용을 잃거나,
 * 받아 온 PDF가 회칙 계약에 걸려 업로드 자체가 거절된다.
 */
class RagDocumentFormatTest {

    @Test
    void markdownIsStructuredAndOfficeFormatsAreGeneric() {
        assertThat(RagDocumentFormat.fromFileName("회칙개정_2026_개정안전문.md"))
                .isEqualTo(RagDocumentFormat.MARKDOWN);
        assertThat(RagDocumentFormat.MARKDOWN.getDocumentType())
                .isEqualTo(RagDocumentType.STRUCTURED);

        assertThat(RagDocumentFormat.fromFileName("숭실대 학칙 발췌.pdf"))
                .isEqualTo(RagDocumentFormat.PDF);
        assertThat(RagDocumentFormat.fromFileName("학술국 운영 세칙.docx"))
                .isEqualTo(RagDocumentFormat.DOCX);
        assertThat(RagDocumentFormat.PDF.getDocumentType()).isEqualTo(RagDocumentType.GENERIC);
        assertThat(RagDocumentFormat.DOCX.getDocumentType()).isEqualTo(RagDocumentType.GENERIC);
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
     * 쪽은 «내용을 고친다»라 겹치지 않는다. 그 안에서 확장자마다 코드를 나누지는 않는다(#150).
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

    /** `.doc`·`.md.txt`처럼 **비슷하지만 다른** 이름이 통과하지 않는다 */
    @Test
    void doesNotAcceptLookalikes() {
        assertThatThrownBy(() -> RagDocumentFormat.fromFileName("옛날세칙.doc"))
                .isInstanceOf(GeneralException.class);
        assertThatThrownBy(() -> RagDocumentFormat.fromFileName("회칙.md.txt"))
                .isInstanceOf(GeneralException.class);
    }
}
