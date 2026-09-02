package org.sscc.ssccopsserver.global.config;

import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/*
 * 이 API 자신의 공개 주소 (#208 · 예: https://api.sscc.club).
 *
 * **R2PublicBaseUrl을 대신한다.** 그쪽은 "버킷의 공개 읽기 도메인"이었고, 행사 이미지와 학술
 * 인증사진이 버킷 하나를 나눠 쓰면서 성립할 수 없게 됐다(ssccops#156 — 공개 접근은 버킷 단위라
 * 접두사로 가를 수 없어 버킷을 공개하면 얼굴이 찍힌 인증사진이 함께 열린다). 버킷을 비공개로
 * 유지하기로 하면서 필요한 값이 바뀌었다 — 이제 필요한 것은 **우리 서버의 주소**다. 행사
 * 이미지는 우리 도메인의 리다이렉트 엔드포인트로 읽고, 그 주소를 여기서 조립한다.
 *
 * ── 값이 없으면 부팅이 실패한다 (#216) ──────────────────────────
 *
 * 처음에는 조립 시점에 거절했다. `R2PublicBaseUrl`에서 물려받은 판단이었고 근거는 "부팅에서
 * 던지면 이 값을 쓰지 않는 기능까지 함께 뜨지 못한다"였는데, **이 값에는 그 근거가 성립하지
 * 않는다.**
 *
 * 옛 값은 한 기능(행사 이미지)만 쓰는 값이었다 — 학술 인증사진은 그 값 없이도 업로드·조회가
 * 성립했으므로, 부팅을 세우면 관계없는 기능까지 죽었다. 반면 이 값은 **배포 그 자체의 속성**
 * 이다. 비어 있을 정당한 이유가 어느 환경에도 없고, 이 값이 없는 서버는 "일부 기능이 안 되는
 * 서버"가 아니라 설정이 덜 된 서버다.
 *
 * 지연 검증의 대가는 실제로 치렀다(ssccops#157) — dev에 값을 넣지 않은 채 배포됐고, 몇 시간 뒤
 * 수동 검증에서 발급이 500으로 죽었다. 던진 것은 IllegalStateException이라 전용 핸들러가 없어
 * `Exception.class` 폴백으로 떨어졌고, 응답에는 INTERNAL_SERVER_ERROR만 실려 **화면에서는
 * 설정 문제라는 사실이 보이지 않았다.** 부팅에서 잡으면 그 실패가 배포 로그 첫 줄에서 드러난다.
 *
 * 그래서 검사는 생성자에 있고, 조립 경로에는 남기지 않는다 — 부팅을 통과한 인스턴스는 언제나
 * 쓸 수 있는 값을 들고 있다.
 */
@Component
public class AppPublicBaseUrl {

    /** 정규화가 끝난 값 — 끝 슬래시가 없고, 스킴이 붙어 있다 */
    private final String baseUrl;

    /*
     * dev·prod 설정에는 기본값이 없다(`${DEV_APP_PUBLIC_BASE_URL}`) — 환경변수가 아예 없으면
     * 스프링이 그 이름을 짚어 부팅을 세운다. 여기서 보는 것은 **값은 있는데 쓸 수 없는 경우**다.
     * local만 http://localhost:8080 기본값을 갖는다(주소가 고정이라 설정을 요구할 이유가 없다).
     */
    public AppPublicBaseUrl(@Value("${app.public-base-url:}") String publicBaseUrl) {
        String trimmed = publicBaseUrl == null ? "" : publicBaseUrl.trim();

        if (trimmed.isBlank()) {
            throw new IllegalStateException(
                    "app.public-base-url 이 비어 있습니다 — 이 API의 공개 주소를 넣으세요"
                            + "(예: https://api.sscc.club). 행사 이미지 주소를 이 값으로"
                            + " 조립하므로 없이는 서버를 띄우지 않습니다.");
        }

        /*
         * 스킴까지 본다. 호스트만 붙여 넣는 실수(`dev.api.sscc-ssu.com`)가 그럴듯하고, 그 값으로
         * 조립한 주소는 마크다운에서 **상대 경로**가 되어 저장된 본문에 그대로 굳는다 — 나중에
         * 고치려면 저장된 본문을 전부 치환하는 수밖에 없다. 부팅에서 잡는 지금은 이 검사가
         * 공짜다.
         */
        String lowered = trimmed.toLowerCase(Locale.ROOT);
        if (!lowered.startsWith("https://") && !lowered.startsWith("http://")) {
            throw new IllegalStateException(
                    "app.public-base-url 에 스킴이 없습니다: "
                            + trimmed
                            + " — https:// 또는 http:// 로 시작해야 합니다. 스킴 없는 값으로"
                            + " 조립한 주소는 화면에서 상대 경로로 읽혀 행사 본문에 굳습니다.");
        }

        // 끝의 슬래시를 떼어 조립한 주소에 `//`가 생기지 않게 한다. 한 번만 하면 되는 일이다
        this.baseUrl = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    /** 절대 경로(`/`로 시작)를 이 API의 공개 주소에 붙여 절대 URL로 만든다. */
    public String urlOf(String path) {
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException("공개 주소로 조립할 경로는 '/'로 시작해야 합니다: " + path);
        }
        return baseUrl + path;
    }
}
