package org.sscc.ssccopsserver.domain.assistant.dto;

import java.util.List;

/*
 * 평문 추출 결과 — 페이지의 나열 (#398 · 기획안 §5.4).
 *
 * `RegulationDocument`(장·조 트리)와 나란한 자리이며, 둘 다 `DocumentChunker`로 들어가 같은
 * `Document[]`로 나간다. **이 타입이 트리를 흉내 내지 않는 것**이 `GENERIC`의 전부다 — 받아 온
 * 문서에 우리 회칙의 구조가 있을 이유가 없고, 평문에서 「제○조」를 정규식으로 긁어 조항 인용을
 * 흉내 내는 안은 기각했다(§5.2).
 */
public record ExtractedDocument(List<ExtractedPage> pages) {

    /**
     * 페이지 경계가 있는 문서인가 — PDF는 참, DOCX는 거짓.
     *
     * <p><b>형식이 아니라 추출 결과에서 읽는다.</b> 「DOCX에는 페이지가 없다」는 Tika가 그렇게 주더라는 실측이지 우리가 정한 규칙이 아니므로, 그 사실이
     * 바뀌면 이 값도 따라 바뀌는 편이 맞다.
     */
    public boolean paginated() {
        return !pages.isEmpty() && pages.get(0).number() != null;
    }

    /** 한 글자도 추출되지 않았는가 — 스캔 이미지 PDF가 여기 걸려 400이 된다 */
    public boolean blank() {
        return pages.stream().allMatch(ExtractedPage::blank);
    }
}
