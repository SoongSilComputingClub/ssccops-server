package org.sscc.ssccopsserver.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/*
 * 이 API의 공개 주소 조립 (#208).
 *
 * 조립한 주소는 행사 본문 마크다운에 문자열로 굳으므로 **빈 값에서 조용히 넘어가지 않는다** —
 * 그때는 저장된 본문을 전부 치환하는 것 말고 되돌릴 방법이 없다(R2PublicBaseUrl이 지키던 것을
 * 그대로 물려받았다). 거절이 생성이 아니라 조립 시점인 것도 같은 이유다: 부팅에서 던지면 이
 * 값을 쓰지 않는 기능까지 함께 뜨지 못한다.
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

    /* 빈 값·공백·null은 모두 같다 — 설정을 넣지 않은 것이다 */
    @Test
    void blankValueRefusesToAssemble() {
        for (String configured : new String[] {"", "   ", null}) {
            assertThatThrownBy(() -> new AppPublicBaseUrl(configured).urlOf("/public/v1/events"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("app.public-base-url");
        }
    }

    /* 상대 경로는 조립할 수 없다 — 부르는 쪽의 버그이므로 조용히 붙이지 않는다 */
    @Test
    void pathMustBeAbsolute() {
        AppPublicBaseUrl baseUrl = new AppPublicBaseUrl("https://api.sscc.club");

        assertThatThrownBy(() -> baseUrl.urlOf("public/v1/events"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
