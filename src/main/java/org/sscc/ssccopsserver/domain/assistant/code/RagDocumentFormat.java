package org.sscc.ssccopsserver.domain.assistant.code;

import java.util.Locale;

import org.sscc.ssccopsserver.domain.assistant.code.error.AssistantErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.AllArgsConstructor;
import lombok.Getter;

/*
 * 올린 파일의 형식 — **확장자 표가 여기 한 벌뿐이다** (#398 · #399 · 기획안 §5.2).
 *
 * ── 왜 `RagDocumentType`에 얹지 않았나 ───────────────────────
 *
 * 같은 표에 두 질문이 걸린다. 업로드(#399)는 «이 파일이 어느 유형인가»(`STRUCTURED`/`GENERIC`)를
 * 묻고, 추출기(#398)는 «어느 파서로 여는가»(PDF냐 DOCX냐)를 묻는다. 유형 enum에 얹으면 뒤의
 * 질문에 답할 수 없어 추출기가 확장자를 한 번 더 읽게 되고, **그 순간 표가 두 벌이 된다** —
 * `.docx`를 더하는 사람이 한쪽만 고치면 «유형은 GENERIC인데 열 파서가 없다»가 성립한다.
 *
 * **요청이 유형을 신고하지 않는다**(#210과 같은 판단). 판정에 쓰는 값이 하나뿐이면 어긋날 수
 * 없다 — 화면이 고른 유형과 실제 파일이 다른 상태가 아예 만들어지지 않는다.
 *
 * 업로드(#399)가 «원본을 어느 Content-Type으로 저장하는가»를 여기에 물으면서 질문이 셋이 됐다.
 * 그 값도 같은 이유로 여기 있다 — 요청이 신고한 MIME을 쓰면 판정의 근거가 둘이 된다.
 */
@Getter
@AllArgsConstructor
public enum RagDocumentFormat {

    /** 우리가 옮겨 적은 회칙·개정안. `RegulationParser`가 장·조 트리로 읽는다 */
    MARKDOWN(".md", "text/markdown", RagDocumentType.STRUCTURED),

    /** 받아 온 학칙 발췌·지침. **페이지 경계가 있는 유일한 형식이다** — 인용의 `p.12`가 거기서 나온다 */
    PDF(".pdf", "application/pdf", RagDocumentType.GENERIC),

    /**
     * 받아 온 세칙.
     *
     * <p><b>DOCX에는 페이지가 없다</b> — 워드가 화면에 그릴 때 계산하는 값이라 파일에 경계가 없고, Tika도 주지 않는다(2026-09-14 실측: 명시적
     * 페이지 나눔조차 줄바꿈 하나로 나온다). 그래서 이 형식의 청크에는 `page` 메타가 붙지 않는다.
     */
    DOCX(
            ".docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            RagDocumentType.GENERIC);

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

    private final RagDocumentType documentType;

    /**
     * 파일 이름으로 형식을 고른다. 모르는 확장자는 <b>400 {@code RAG_DOCUMENT_UNSUPPORTED_TYPE}</b>이다.
     *
     * <p>파싱 실패(계약 위반·빈 추출)와 코드를 나눈 것은 <b>운영진이 할 일이 다르기 때문</b>이다 — 이쪽은 «받는 형식으로 바꿔 다시 올리세요»이고 그쪽은
     * «파일의 몇째 줄을 고치세요»다. 그 안에서 확장자마다 코드를 나누지는 않는다(#150).
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
                "`.md` · `.pdf` · `.docx`만 올릴 수 있습니다. 올린 파일: " + describe(fileName));
    }

    /** 오류 문구에 파일명을 실을 때의 상한 — 사람이 읽는 한 줄이지 경로 덤프가 아니다 */
    private static String describe(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "(이름 없음)";
        }
        return fileName.length() <= 60 ? fileName : fileName.substring(0, 60) + "…";
    }
}
