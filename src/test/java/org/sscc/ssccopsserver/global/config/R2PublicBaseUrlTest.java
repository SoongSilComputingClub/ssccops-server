package org.sscc.ssccopsserver.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/*
 * 공개 읽기 주소의 검증 (#200).
 *
 * 이 검사가 없던 동안 `r2.public-base-url`에 S3 API 엔드포인트가 들어간 채로 두 도메인이 함께
 * 돌았고, 그 값으로 조립된 주소가 행사 본문 마크다운에 문자열로 굳었다 — 저장된 본문을 전부
 * 치환하는 것 말고는 되돌릴 방법이 없는 종류의 실수라 **부팅을 세운다**.
 *
 * 컨텍스트를 띄우지 않고 생성자를 직접 부르는 것은 확인하려는 것이 규칙 하나뿐이기 때문이다 —
 * 이 빈이 실제로 부팅을 세운다는 사실은 스프링이 생성자 예외를 그대로 올린다는 성질이다.
 */
class R2PublicBaseUrlTest {

    @Test
    void assemblesUrlFromObjectKey() {
        R2PublicBaseUrl baseUrl = new R2PublicBaseUrl("https://images.example.com");

        assertThat(baseUrl.urlOf("academic-programs/1/sessions/2/a.png"))
                .isEqualTo("https://images.example.com/academic-programs/1/sessions/2/a.png");
    }

    // 끝의 슬래시가 있어도 `//`가 생기지 않는다
    @Test
    void normalizesTrailingSlash() {
        R2PublicBaseUrl baseUrl = new R2PublicBaseUrl("https://images.example.com/ ".trim());

        assertThat(baseUrl.urlOf("events/1/a.png"))
                .isEqualTo("https://images.example.com/events/1/a.png");
    }

    /*
     * S3 API 엔드포인트는 SigV4 서명을 요구하므로 서명 없는 GET(브라우저의 <img src>)에 언제나
     * 401을 돌려준다 — 결과가 "비어 있는 것"과 같으므로 같이 거절한다.
     */
    @Test
    void rejectsS3ApiEndpoint() {
        assertThatThrownBy(
                        () ->
                                new R2PublicBaseUrl(
                                        "https://abc123.r2.cloudflarestorage.com/ssccops-local"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("r2.cloudflarestorage.com");
    }

    @Test
    void rejectsBlankValue() {
        assertThatThrownBy(() -> new R2PublicBaseUrl("  "))
                .isInstanceOf(IllegalStateException.class);
    }
}
