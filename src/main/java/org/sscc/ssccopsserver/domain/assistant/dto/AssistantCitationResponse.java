package org.sscc.ssccopsserver.domain.assistant.dto;

import org.sscc.ssccopsserver.domain.assistant.code.CitationType;

/*
 * 답변이 기댄 근거 하나 (#403 · 기획안 §6.3).
 *
 * ══ 두 모양이 한 record에 있다 ══════════════════════════════════
 *
 * `citationType`이 **어느 필드가 채워졌는가**를 말한다 — `ARTICLE`이면 `chapter`·`article`·
 * `clause`·`supplementary`, `PAGE`면 `page`다. **반대쪽은 `null`이며 서버가 대체값을 만들지
 * 않는다.** `"—"` 같은 값을 채우면 화면이 «값이 없다»와 «없는 것이 정상이다»를 구별하지 못하고,
 * 그 구별이 실제로 필요하다: DOCX에는 페이지가 없어서(#398) `PAGE`인데 `page`가 비는 것이
 * **정상**이고, 그때 인용 카드는 문서명까지만 그린다.
 *
 * 유형을 하나로 합치는 안을 버린 이유가 그것이고, **한 답변에 둘이 섞이는 것이 정상이다** —
 * 회칙 제27조가 세부 규정을 다른 문서에 위임하고 있어 실제로 섞인다.
 *
 * ══ `supplementary`가 부칙 여부다 ═══════════════════════════════
 *
 * **이것이 없으면 `제1조`가 두 곳을 가리킨다** — 부칙에서 조번호가 1로 리셋되기 때문이다
 * (§5.3 · 본칙 제1조는 «명칭», 부칙 제1조는 «용어»다). `article`에 이미 «부칙 »이 붙어 있지만
 * 화면이 그 문자열을 파싱해 판단하게 두지 않는다.
 *
 * ══ 싣지 않는 것 ═══════════════════════════════════════════════
 *
 * **판본 번호(`docVer`)가 있던 자리다**(ADR-0034). 문서 한 건이 곧 그 규정이라 인용 카드에
 * 「v1」을 그릴 것이 없다.
 *
 * 유사도 점수를 내리지 않는다. 화면이 그 수를 그리면 «0.62점짜리 근거»를 사용자가 해석하게
 * 되는데, 임계값 아래는 애초에 답변에 닿지 않고(§6.1) 그 위의 순위는 우리가 보증하는 값이
 * 아니다. 청크 식별자도 내리지 않는다 — 다시 부를 수 있는 API가 없다.
 */
public record AssistantCitationResponse(
        CitationType citationType,
        String docTitle,
        String chapter,
        Boolean supplementary,
        String article,
        String clause,
        Integer page,
        String snippet) {

    /** 조항 인용 — `제2장 회원 · 제7조 (회원의 구분) · 6항`. `page`는 언제나 null이다 */
    public static AssistantCitationResponse article(
            String docTitle,
            String chapter,
            boolean supplementary,
            String article,
            String clause,
            String snippet) {

        return new AssistantCitationResponse(
                CitationType.ARTICLE,
                docTitle,
                chapter,
                supplementary,
                article,
                clause,
                null,
                snippet);
    }

    /** 페이지 인용 — `2026 지원금 집행 지침 · p.12`. <b>`page`가 null인 것은 DOCX라는 뜻이다</b>(#398) */
    public static AssistantCitationResponse page(String docTitle, Integer page, String snippet) {

        return new AssistantCitationResponse(
                CitationType.PAGE, docTitle, null, null, null, null, page, snippet);
    }
}
