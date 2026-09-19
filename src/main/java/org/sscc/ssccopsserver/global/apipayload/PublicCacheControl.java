package org.sscc.ssccopsserver.global.apipayload;

import java.time.Duration;

import org.springframework.http.CacheControl;

/*
 * 익명 콘텐츠 응답의 Cache-Control (ssccops#381 · ADR-0038 · ADR-0030 «캐시는 헤더로»).
 *
 * `public, s-maxage=300, stale-while-revalidate=600` — CDN(www 앞의 Vercel·Cloudflare)이 5분
 * 들고 있다가 뒤 10분은 낡은 응답을 내주며 뒤에서 새로 받는다. 브라우저용 max-age는 두지
 * 않는다 — 게시 취소가 브라우저 캐시에까지 5분 남는 것은 CDN과 달리 운영진이 «다시 열어
 * 보라»고 말할 수 없다. 대가는 ADR-0038이 적어 둔 «게시 취소가 최대 5분 늦는다»이고, 그
 * 이상(무효화 API)은 플랫폼 중립 규칙에 어긋나 두지 않는다.
 *
 * global에 있는 것은 콘텐츠(pages·posts)와 폼(forms/open) 두 도메인이 같은 값을 쓰기 때문이다 —
 * 한쪽 도메인에 두면 다른 쪽이 그 도메인에 의존하게 되고(content ↔ form 순환), 값이 두 벌이면
 * 한쪽만 바뀐다. 익명이 아닌 응답에는 쓰지 않는다 — 인증 응답에 public이 붙으면 CDN이 남의
 * 응답을 내준다.
 */
public final class PublicCacheControl {

    public static final Duration SHARED_MAX_AGE = Duration.ofSeconds(300);
    public static final Duration STALE_WHILE_REVALIDATE = Duration.ofSeconds(600);

    /** 헤더 값 그대로 — 테스트가 이 문자열과 대조한다 */
    public static final String HEADER_VALUE = "public, s-maxage=300, stale-while-revalidate=600";

    private PublicCacheControl() {}

    public static CacheControl anonymousContent() {
        return CacheControl.empty()
                .cachePublic()
                .sMaxAge(SHARED_MAX_AGE)
                .staleWhileRevalidate(STALE_WHILE_REVALIDATE);
    }
}
