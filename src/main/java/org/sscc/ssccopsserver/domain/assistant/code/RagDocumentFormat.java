package org.sscc.ssccopsserver.domain.assistant.code;

import java.util.Locale;

import org.sscc.ssccopsserver.domain.assistant.code.error.AssistantErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.AllArgsConstructor;
import lombok.Getter;

/*
 * 올린 파일의 형식 — **확장자 표가 여기 한 벌뿐이다** (#398 · #399 · #445 · 기획안 §5.2).
 *
 * ── 확장자는 유형을 «정하지» 않는다 — 후보만 정한다 (#445) ───
 *
 * 처음에는 이 표가 `RagDocumentType`까지 단정했다(`.md` → STRUCTURED · 나머지 → GENERIC).
 * 그래서 회칙이 아닌 `.md`는 **코퍼스에 들어갈 방법이 아예 없었다** — `RegulationParser`의 계약을
 * 어기면 400이고, 확장자를 바꾸지 않는 한 평문 경로로 갈 수 없었다.
 *
 * **확장자가 실제로 말하는 것은 «어느 파서로 열어 볼 수 있는가»이지 «이 파일이 회칙이다»가
 * 아니다.** 그래서 지금 이 표가 주는 값은 둘이다 — 평문을 어떻게 뽑는가({@link Extraction})와
 * 회칙 파서를 먼저 시도해 볼 만한가({@code regulationCandidate}). **유형을 정하는 것은 파싱
 * 결과**이며 그 판단은 `RagDocumentServiceImpl.resolveType` 한 곳에 있다.
 *
 * **요청이 유형을 신고하지 않는다는 원칙은 그대로다**(#210). 판정 근거가 「확장자」에서
 * 「확장자 + 파싱 결과」로 넓어졌을 뿐 둘 다 서버가 파일에서 직접 읽는 값이라, 화면이 고른
 * 유형과 실제 파일이 다른 상태는 여전히 만들어지지 않는다.
 *
 * ── 왜 `RagDocumentType`에 얹지 않았나 ───────────────────────
 *
 * 같은 표에 여러 질문이 걸린다. 업로드(#399)는 «이 파일을 어떻게 여는가»를 묻고 추출기(#398)는
 * «어느 파서인가»를 묻는다. 유형 enum에 얹으면 뒤의 질문에 답할 수 없어 추출기가 확장자를 한 번
 * 더 읽게 되고, **그 순간 표가 두 벌이 된다** — 확장자를 더하는 사람이 한쪽만 고치면 «받기는
 * 받는데 열 파서가 없다»가 성립한다.
 *
 * 업로드(#399)가 «원본을 어느 Content-Type으로 저장하는가»를 여기에 물으면서 질문이 셋이 됐다.
 * 그 값도 같은 이유로 여기 있다 — 요청이 신고한 MIME을 쓰면 판정의 근거가 둘이 된다.
 */
@Getter
@AllArgsConstructor
public enum RagDocumentFormat {

    /**
     * 우리가 옮겨 적은 회칙·개정안, 그리고 **회칙이 아닌 마크다운 전부**.
     *
     * <p><b>회칙 파서를 먼저 시도하는 유일한 형식이다</b> — 통과하면 조 단위 인용(`제7조 6항`)을 얻고, 계약을 어기면 평문으로 떨어져 문서명까지 인용된다
     * (#445). 떨어진 것은 목록의 유형 칸에 그대로 드러나므로 조용한 강등이 아니다.
     */
    MARKDOWN(".md", "text/markdown", Extraction.PLAIN, true),

    /** 메모·회의록처럼 구조가 없는 글. 파서가 필요 없다 — 바이트가 곧 본문이다 (#445) */
    TEXT(".txt", "text/plain", Extraction.PLAIN, false),

    /** 받아 온 학칙 발췌·지침. **페이지 경계가 있는 유일한 형식이다** — 인용의 `p.12`가 거기서 나온다 */
    PDF(".pdf", "application/pdf", Extraction.PDF, false),

    /**
     * 받아 온 세칙.
     *
     * <p><b>DOCX에는 페이지가 없다</b> — 워드가 화면에 그릴 때 계산하는 값이라 파일에 경계가 없고, Tika도 주지 않는다(2026-09-14 실측: 명시적
     * 페이지 나눔조차 줄바꿈 하나로 나온다). 그래서 이 형식의 청크에는 `page` 메타가 붙지 않는다.
     */
    DOCX(
            ".docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            Extraction.OOXML,
            false),

    /** 발표 자료 (#445). DOCX와 같이 페이지가 없다 — Tika가 슬라이드를 쪽으로 열어 주지 않는다 */
    PPTX(
            ".pptx",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            Extraction.OOXML,
            false),

    /** 표 (#445). 셀이 평문으로 이어져 나오므로 문장 단위 검색에는 약하지만 «없는 것»보다는 낫다 */
    XLSX(
            ".xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            Extraction.OOXML,
            false),

    /** 옛 워드 (#445). OLE2라 파서가 다르다 */
    DOC(".doc", "application/msword", Extraction.OLE2, false),

    /** 옛 발표 자료 (#445) */
    PPT(".ppt", "application/vnd.ms-powerpoint", Extraction.OLE2, false),

    /** 옛 표 (#445) */
    XLS(".xls", "application/vnd.ms-excel", Extraction.OLE2, false);

    private final String extension;

    /*
     * R2에 원본을 올릴 때 실을 Content-Type (#399).
     *
     * **요청이 신고한 값(`MultipartFile.getContentType()`)을 쓰지 않는다.** 브라우저·OS에 따라
     * `.md`가 `text/plain`·`application/octet-stream`으로 제각각 오고(#84가 CSV에서 겪은 것과
     * 같다), 무엇보다 그 값은 파일의 정체가 아니라 신고다 — 형식을 정하는 값이 확장자 하나뿐인
     * 것이 이 표의 요점이므로 저장할 때 붙이는 형식도 여기서 온다(`ImageFileType`과 같은 자리).
     */
    private final String contentType;

    /** 평문을 어떻게 뽑는가 — `GenericTextExtractor`가 이 값으로 파서를 고른다 */
    private final Extraction extraction;

    /**
     * 회칙 파서(`RegulationParser`)를 먼저 시도해 볼 만한 형식인가 (#445).
     *
     * <p><b>«시도한다»이지 «회칙이다»가 아니다.</b> 실패는 400이 아니라 평문 경로로의 이동이다.
     */
    private final boolean regulationCandidate;

    /**
     * 평문을 뽑는 방법. <b>확장자마다 파서를 고정하며 {@code AutoDetectParser}를 쓰지 않는다</b> — 코퍼스에 올라오는 것은 외부에서 받아 온
     * 파일이라 실행될 수 있는 파서가 적을수록 좋다(#398 · `GenericTextExtractor` 주석).
     */
    public enum Extraction {

        /** 파서 없이 UTF-8로 읽는다 — `.md`·`.txt` */
        PLAIN,

        /** `PDFParser` — 쪽 경계를 주는 유일한 길 */
        PDF,

        /** `OOXMLParser` — `.docx`·`.pptx`·`.xlsx` */
        OOXML,

        /** `OfficeParser` — OLE2 `.doc`·`.ppt`·`.xls` */
        OLE2
    }

    /**
     * 파일 이름으로 형식을 고른다. 모르는 확장자는 <b>400 {@code RAG_DOCUMENT_UNSUPPORTED_TYPE}</b>이다.
     *
     * <p>파싱 실패(계약 위반·빈 추출)와 코드를 나눈 것은 <b>운영진이 할 일이 다르기 때문</b>이다 — 이쪽은 «받는 형식으로 바꿔 다시 올리세요»이고 그쪽은
     * «글자를 선택할 수 있는 파일로 다시 올리세요»다. 그 안에서 확장자마다 코드를 나누지는 않는다(#150).
     *
     * <p><b>회칙 계약 위반은 더 이상 여기로 오지 않는다</b>(#445) — 평문으로 떨어지므로 거절이 아니다.
     */
    public static RagDocumentFormat fromFileName(String fileName) {
        String lowered = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        for (RagDocumentFormat format : values()) {
            if (lowered.endsWith(format.extension)) {
                return format;
            }
        }
        throw new GeneralException(
                AssistantErrorCode.RAG_DOCUMENT_UNSUPPORTED_TYPE,
                "받지 않는 형식입니다. 올릴 수 있는 것: " + supported() + ". 올린 파일: " + describe(fileName));
    }

    /** 오류 문구가 표를 그대로 읽는다 — 확장자를 더할 때 문구를 따로 고치지 않는다 */
    private static String supported() {
        StringBuilder joined = new StringBuilder();
        for (RagDocumentFormat format : values()) {
            joined.append(joined.isEmpty() ? "" : " · ")
                    .append('`')
                    .append(format.extension)
                    .append('`');
        }
        return joined.toString();
    }

    /** 오류 문구에 파일명을 실을 때의 상한 — 사람이 읽는 한 줄이지 경로 덤프가 아니다 */
    private static String describe(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "(이름 없음)";
        }
        return fileName.length() <= 60 ? fileName : fileName.substring(0, 60) + "…";
    }
}
