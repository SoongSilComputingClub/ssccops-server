package org.sscc.ssccopsserver.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/*
 * 이 API 자신의 공개 주소를 조립하는 자리 (#208 · 예: https://api.sscc.club).
 *
 * **R2PublicBaseUrl을 대신한다.** 그쪽은 "버킷의 공개 읽기 도메인"이었고, 행사 이미지와 학술
 * 인증사진이 버킷 하나를 나눠 쓰면서 성립할 수 없게 됐다(ssccops#156 — 공개 접근은 버킷 단위라
 * 접두사로 가를 수 없어 버킷을 공개하면 얼굴이 찍힌 인증사진이 함께 열린다). 버킷을 비공개로
 * 유지하기로 하면서 필요한 값이 바뀌었다 — 이제 필요한 것은 **우리 서버의 주소**다. 행사
 * 이미지는 우리 도메인의 리다이렉트 엔드포인트로 읽고, 그 주소를 여기서 조립한다.
 *
 * **비어 있으면 조립을 거절한다.** 옛 클래스와 같은 태도이고 이유도 같다 — 이 값으로 만든
 * 주소는 행사 본문 마크다운에 문자열로 굳으므로, 조용히 넘어가면 나중에 저장된 본문을 전부
 * 치환하는 것 말고는 고칠 방법이 없다. 요청을 실패시키면 저장될 값이 애초에 만들어지지 않는다.
 *
 * 다만 **잘못된 값의 성격은 옛 값과 다르다.** 옛 값은 S3 API 엔드포인트가 들어가도 브라우저가
 * 조용히 401을 받을 뿐이라 몇 달을 모르고 돌 수 있었지만(#200), 이 값이 틀리면 우리 도메인의
 * 링크가 통째로 깨져 첫 화면에서 드러난다. 그래서 호스트 형태를 더 따지지 않고 빈 값만 본다 —
 * 사람이 알아채지 못하는 실패가 아니다.
 *
 * 거절하는 시점을 부팅이 아니라 조립할 때로 두는 것도 옛 클래스에서 물려받았다. 부팅에서
 * 던지면 이 값을 쓰지 않는 기능까지 함께 뜨지 못한다.
 */
@Component
public class AppPublicBaseUrl {

    private final String configuredValue;

    public AppPublicBaseUrl(@Value("${app.public-base-url:}") String publicBaseUrl) {
        this.configuredValue = publicBaseUrl == null ? "" : publicBaseUrl.trim();
    }

    /** 절대 경로(`/`로 시작)를 이 API의 공개 주소에 붙여 절대 URL로 만든다. 값이 비어 있으면 여기서 끊는다. */
    public String urlOf(String path) {
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException("공개 주소로 조립할 경로는 '/'로 시작해야 합니다: " + path);
        }
        return baseUrl() + path;
    }

    private String baseUrl() {
        if (configuredValue.isBlank()) {
            throw new IllegalStateException(
                    "app.public-base-url 이 비어 있습니다 — 이 API의 공개 주소를 조립할 수 없습니다."
                            + " 외부에서 이 서버에 닿는 주소를 넣으세요(예: https://api.sscc.club).");
        }
        // 끝의 슬래시를 떼어 조립한 주소에 `//`가 생기지 않게 한다
        return configuredValue.endsWith("/")
                ? configuredValue.substring(0, configuredValue.length() - 1)
                : configuredValue;
    }
}
