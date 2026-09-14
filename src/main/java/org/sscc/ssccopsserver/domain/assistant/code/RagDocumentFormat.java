package org.sscc.ssccopsserver.domain.assistant.code;

import java.util.Locale;

import org.sscc.ssccopsserver.domain.assistant.code.error.AssistantErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.AllArgsConstructor;
import lombok.Getter;

/*
 * 올린 파일의 형식 — **확장자 표가 여기 한 벌뿐이다** (#398 · 기획안 §5.2).
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
 */
@Getter
@AllArgsConstructor
public enum RagDocumentFormat {

    /** 우리가 옮겨 적은 회칙·개정안. `RegulationParser`가 장·조 트리로 읽는다 */
    MARKDOWN(".md", RagDocumentType.STRUCTURED),

    /** 받아 온 학칙 발췌·지침. **페이지 경계가 있는 유일한 형식이다** — 인용의 `p.12`가 거기서 나온다 */
    PDF(".pdf", RagDocumentType.GENERIC),

    /**
     * 받아 온 세칙.
     *
     * <p><b>DOCX에는 페이지가 없다</b> — 워드가 화면에 그릴 때 계산하는 값이라 파일에 경계가 없고, Tika도 주지 않는다(2026-09-14 실측: 명시적
     * 페이지 나눔조차 줄바꿈 하나로 나온다). 그래서 이 형식의 청크에는 `page` 메타가 붙지 않는다.
     */
    DOCX(".docx", RagDocumentType.GENERIC);

    private final String extension;
    private final RagDocumentType documentType;

    /**
     * 파일 이름으로 형식을 고른다. 모르는 확장자는 <b>400</b>이다.
     *
     * <p>사유마다 코드를 만들지 않는다(#150) — 화면이 할 안내는 어느 확장자든 «받는 형식으로 바꿔 다시 올리세요» 하나다.
     */
    public static RagDocumentFormat fromFileName(String fileName) {
        String lowered = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        for (RagDocumentFormat format : values()) {
            if (lowered.endsWith(format.extension)) {
                return format;
            }
        }
        throw new GeneralException(
                AssistantErrorCode.RAG_DOCUMENT_PARSE_FAILED,
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
