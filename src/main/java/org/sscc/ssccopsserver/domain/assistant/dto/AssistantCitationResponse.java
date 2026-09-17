package org.sscc.ssccopsserver.domain.assistant.dto;

import org.sscc.ssccopsserver.domain.assistant.code.CitationType;

/*
 * 답변이 기댄 근거 하나 (#403 · #447 · 기획안 §6.3).
 *
 * ══ `ref`가 본문의 `[3]`과 짝이다 (#447) ════════════════════════
 *
 * 모델은 조 문자열이 아니라 **발췌 번호**를 쓴다 — 본문에 `[3]`이 박히고 이 record의 `ref`가 그
 * 3이다. **순서가 아니라 번호다**: 발췌 여덟 개 중 3번과 7번만 인용되면 배열에는 둘이 들어오고
 * 그 `ref`는 3과 7이다. 다시 1·2로 매기지 않는 것은 **그러려면 이미 흘려보낸 본문을 고쳐 써야
 * 하기 때문**이다(스트리밍에서 불가능하고, 하지 않아도 화면이 할 일은 같다).
 *
 * `marker`는 **서버가 만든 짧은 표기**다 — `제7조` · `부칙 제3조` · `p.12` · 문서명
 * ({@code RetrievedChunk.marker()}). 모델이 이 문자열을 쓰지 않는다는 것이 #447의 요점이며,
 * 그래서 **틀린 조 번호가 발생할 수 없다.** 본문의 `[3]`을 그대로 둘지 이 표기로 갈아 그릴지는
 * 화면이 고른다 — 서버는 둘 다 내리고 고르지 않는다(갈아 그리면 길이가 바뀌어 스트리밍 중에는
 * 할 수 없고, 다 받은 뒤에는 할 수 있다).
 *
 * ══ 두 모양이 한 record에 있다 ══════════════════════════════════
 *
 * `citationType`이 **어느 필드가 채워졌는가**를 말한다 — `ARTICLE`이면 `chapter`·`article`·
 * `supplementary`, `PAGE`면 `page`다. **반대쪽은 `null`이며 서버가 대체값을 만들지
 * 않는다.** `"—"` 같은 값을 채우면 화면이 «값이 없다»와 «없는 것이 정상이다»를 구별하지 못하고,
 * 그 구별이 실제로 필요하다: DOCX에는 페이지가 없어서(#398) `PAGE`인데 `page`가 비는 것이
 * **정상**이고, 그때 인용 카드는 문서명까지만 그린다.
 *
 * 유형을 하나로 합치는 안을 버린 이유가 그것이고, **한 답변에 둘이 섞이는 것이 정상이다** —
 * 회칙 제27조가 세부 규정을 다른 문서에 위임하고 있어 실제로 섞인다.
 *
 * ⚠️ **`clause`는 언제나 `null`이다** (#447). 항 표기는 모델이 `[제7조 6항]`이라고 써 줄 때만
 * 알 수 있던 값인데, 대괄호에 번호만 들어오면서 «이 발췌의 어느 항을 가리키는가»를 서버가 알
 * 길이 없어졌다. 지어내지 않는다 — 필드를 지우지 않은 것은 화면 계약을 깨지 않기 위해서이고
 * (응답 필드 삭제는 OpenAPI 하위 호환 게이트가 막는다), 항이 실제로 무엇을 말하는지는
 * `snippet`이 그대로 보여 준다. 되살리려면 청크 메타에 «이 청크가 담은 항 범위»가 있어야 한다.
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
        int ref,
        String marker,
        CitationType citationType,
        String docTitle,
        String chapter,
        Boolean supplementary,
        String article,
        String clause,
        Integer page,
        String snippet) {

    /** 조항 인용 — `제2장 회원 · 제7조 (회원의 구분)`. `page`는 언제나 null이고 `clause`도 그렇다(클래스 주석) */
    public static AssistantCitationResponse article(
            int ref,
            String marker,
            String docTitle,
            String chapter,
            boolean supplementary,
            String article,
            String snippet) {

        return new AssistantCitationResponse(
                ref,
                marker,
                CitationType.ARTICLE,
                docTitle,
                chapter,
                supplementary,
                article,
                null,
                null,
                snippet);
    }

    /** 페이지 인용 — `2026 지원금 집행 지침 · p.12`. <b>`page`가 null인 것은 DOCX라는 뜻이다</b>(#398) */
    public static AssistantCitationResponse page(
            int ref, String marker, String docTitle, Integer page, String snippet) {

        return new AssistantCitationResponse(
                ref, marker, CitationType.PAGE, docTitle, null, null, null, null, page, snippet);
    }
}
