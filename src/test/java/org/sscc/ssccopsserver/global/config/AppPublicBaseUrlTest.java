package org.sscc.ssccopsserver.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/*
 * 이 API의 공개 주소 조립 (#208 · #216).
 *
 * 조립한 주소는 행사 본문 마크다운에 문자열로 굳으므로 **쓸 수 없는 값에서 조용히 넘어가지
 * 않는다** — 그때는 저장된 본문을 전부 치환하는 것 말고 되돌릴 방법이 없다.
 *
 * **거절 시점이 생성자다**(#216). 처음에는 조립할 때 던졌는데, 그러면 값을 넣지 않은 채로도
 * 서버가 떠서 실패가 몇 시간 뒤 이미지 발급 500으로 발견된다(ssccops#157에서 실제로 그랬다).
 * 이 값은 배포 그 자체의 속성이라 비어 있을 정당한 이유가 없으므로 부팅에서 세운다.
 */
class AppPublicBaseUrlTest {

    @Test
    void assemblesAbsoluteUrlFromPath() {
        AppPublicBaseUrl baseUrl = new AppPublicBaseUrl("https://api.sscc.club");

        assertThat(baseUrl.urlOf("/public/v1/events/1/images/a.png"))
                .isEqualTo("https://api.sscc.club/public/v1/events/1/images/a.png");
    }

    // 끝의 슬래시가 있어도 `//`가 생기지 않는다
    @Test
    void trailingSlashDoesNotDoubleUp() {
        AppPublicBaseUrl baseUrl = new AppPublicBaseUrl("https://api.sscc.club/");

        assertThat(baseUrl.urlOf("/public/v1/events/1/images/a.png"))
                .isEqualTo("https://api.sscc.club/public/v1/events/1/images/a.png");
    }

    /* 빈 값·공백·null은 모두 같다 — 설정을 넣지 않은 것이고, 그런 서버는 뜨지 않는다 */
    @Test
    void blankValueFailsToStart() {
        for (String configured : new String[] {"", "   ", null}) {
            assertThatThrownBy(() -> new AppPublicBaseUrl(configured))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("app.public-base-url");
        }
    }

    /*
     * 스킴이 없으면 거절한다. 호스트만 붙여 넣는 실수가 그럴듯한데, 그 값으로 조립한 주소는
     * 마크다운에서 상대 경로로 읽혀 본문에 굳는다.
     */
    @Test
    void valueWithoutSchemeFailsToStart() {
        for (String configured : new String[] {"dev.api.sscc-ssu.com", "//api.sscc.club"}) {
            assertThatThrownBy(() -> new AppPublicBaseUrl(configured))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("스킴");
        }
    }

    /* 로컬은 http로 뜬다 — 스킴 검사가 https만 요구하지는 않는다 */
    @Test
    void httpIsAllowed() {
        AppPublicBaseUrl baseUrl = new AppPublicBaseUrl("http://localhost:8080");

        assertThat(baseUrl.urlOf("/public/v1/events/1/images/a.png"))
                .isEqualTo("http://localhost:8080/public/v1/events/1/images/a.png");
    }

    /* 상대 경로는 조립할 수 없다 — 부르는 쪽의 버그이므로 조용히 붙이지 않는다 */
    @Test
    void pathMustBeAbsolute() {
        AppPublicBaseUrl baseUrl = new AppPublicBaseUrl("https://api.sscc.club");

        assertThatThrownBy(() -> baseUrl.urlOf("public/v1/events"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
