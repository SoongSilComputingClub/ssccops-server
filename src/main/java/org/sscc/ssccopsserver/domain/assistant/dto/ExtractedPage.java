package org.sscc.ssccopsserver.domain.assistant.dto;

/*
 * 추출된 한 페이지 (#398 · 기획안 §5.4).
 *
 * **`number`가 `null`이면 그 형식에 페이지가 없다**(DOCX). 「1쪽」으로 채우지 않는 것은 그것이
 * 사실이 아니기 때문이다 — 9쪽에 있는 문장에 `p.1`을 달면 운영진이 확인하러 연 쪽에 그 문장이
 * 없고, **맞을 때도 틀릴 때도 있는 인용은 없는 인용보다 나쁘다**(§5.2). 페이지가 없는 문서는
 * 인용에 페이지를 싣지 않는다.
 */
public record ExtractedPage(Integer number, String text) {

    /** 본문이 한 글자도 없는 페이지. 빈 쪽은 번호를 유지한 채 남는다 — 지우면 뒤쪽 번호가 밀린다 */
    public boolean blank() {
        return text.isBlank();
    }
}
