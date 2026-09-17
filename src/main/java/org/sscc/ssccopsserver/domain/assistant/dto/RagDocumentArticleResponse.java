package org.sscc.ssccopsserver.domain.assistant.dto;

/*
 * 상세의 조 목록 한 줄 — `STRUCTURED`에만 있다 (#401 · 기획안 §10).
 *
 * **`GENERIC`에는 조가 없다.** 평문에서 «제○조»를 정규식으로 긁어 흉내 내지 않는다 —
 * 맞을 때도 틀릴 때도 있는 인용은 없는 인용보다 나쁘다(#398).
 *
 * `chapter`가 함께 실리는 것은 조번호만으로는 맥락이 서지 않기 때문이다 — «제28조 (설치)»가
 * 무엇의 설치인지는 장이 말하고, 그 조는 개정안에서 제5장에서 제8장으로 옮겨 갔다(#397).
 * `revisionMarker`는 원문 그대로의 한국어(«개정»·«신설»)이며 없으면 null이다.
 */
public record RagDocumentArticleResponse(
        String chapter,
        boolean supplementary,
        String label,
        String title,
        String heading,
        String revisionMarker,
        int line) {

    public static RagDocumentArticleResponse of(
            String chapter, boolean supplementary, RegulationArticle article) {

        return new RagDocumentArticleResponse(
                chapter,
                supplementary,
                article.label(),
                article.title(),
                article.heading(),
                article.revisionMarker(),
                article.line());
    }
}
