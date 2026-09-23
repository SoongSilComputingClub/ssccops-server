package org.sscc.ssccopsserver.global.crawler;

/*
 * 검색 크롤러에게 이 API를 어디까지 열지 (#541 · ssccops#482).
 *
 * ── 왜 API에 robots.txt가 필요한가 ───────────────────────────
 * 크롤러는 우리를 찾아온다. 행사 본문 이미지와 포스트 갤러리 이미지가 이 호스트에서 나가고
 * 그 주소가 공개 사이트의 HTML에 그대로 박히기 때문이다
 * (`https://api.…/public/v1/events/{id}/images/{file}`).
 *
 * 그런데 이 문서가 없으면 `/robots.txt`도 인증 규칙에 걸려 **401**이 나가고, **401은 크롤러에게
 * «제한 없음»이다** — Google 문서가 못 박고 있다: 429를 뺀 4xx는 robots.txt가 없는 것으로 보고
 * 크롤링 제한이 없다고 가정한다. 즉 문서를 두지 않는 것은 «막는 것»이 아니라 **`/public/v1`을
 * 통째로 여는 것**이다.
 *
 * ── 왜 `Disallow: /` 한 줄로 끝내지 않나 ─────────────────────
 * 그러면 이미지까지 함께 막혀 공개 사이트가 맞춰 둔 이미지 색인이 죽는다(web#602 · ssccops#444).
 * 그래서 전부 닫고 **이미지 두 갈래만 다시 연다** — robots.txt는 가장 긴 일치가 이기므로
 * 아래 `Allow` 두 줄이 `Disallow: /`를 이긴다.
 *
 * 닫는 쪽에 남는 것이 이 문서의 목적이다. `/public/v1` 아래에는 이미지 말고도 **공개 사이트가
 * 그리는 본문이 JSON으로 한 벌 더** 있고(`/pages/{slug}` · `/posts/{slug}`) **공유 토큰 착지**
 * (`/share/{token}`)가 있다 — 공개 사이트는 그 착지를 `Disallow: /s/`로 이미 막아 두었는데
 * 이쪽만 열려 있으면 한쪽만 막은 셈이 된다.
 */
public final class RobotsTxt {

    /** 크롤러가 호스트마다 이 자리 하나만 본다 — 경로를 바꿀 수 없다 */
    public static final String PATH = "/robots.txt";

    public static final String BODY =
            """
            User-agent: *
            Disallow: /
            Allow: /public/v1/events/*/images/
            Allow: /public/v1/posts/*/images/
            """;

    private RobotsTxt() {}
}
