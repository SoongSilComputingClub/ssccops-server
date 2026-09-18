package org.sscc.ssccopsserver.domain.content.code;

/*
 * 본문 길이 상한 (ssccops#381). 행사 본문(EventServiceImpl · 10만 자)과 같은 값이다 — 같은
 * 편집기·같은 렌더러를 쓰므로 한쪽만 다르면 «행사에서는 저장되는 글이 포스트에서는 거절»이
 * 된다. 서버는 길이만 본다. 마크다운 파싱·raw HTML 차단은 화면(www 렌더러)의 몫이다(ADR-0038).
 */
public final class ContentBody {

    public static final int MAX_LENGTH = 100_000;

    private ContentBody() {}

    public static boolean isTooLarge(String body) {
        return body != null && body.length() > MAX_LENGTH;
    }
}
