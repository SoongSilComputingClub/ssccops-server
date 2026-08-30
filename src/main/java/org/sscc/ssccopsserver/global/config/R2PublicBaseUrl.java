package org.sscc.ssccopsserver.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/*
 * R2 오브젝트의 공개 읽기 주소를 조립하는 자리 (#161 · #200).
 *
 * 원래 EventImageServiceImpl과 SessionFileReferenceServiceImpl이 같은 프로퍼티를 각자 주입받아
 * 각자 정규화했다. 한 벌로 모으는 것은 **검사가 갈리지 않게 하기 위해서다** — 실제로 이 값에
 * S3 API 엔드포인트가 들어간 채로 두 도메인이 함께 몇 달을 돌았고(#200), 그 사이 만들어진 주소가
 * 행사 본문 마크다운에 문자열로 굳었다.
 *
 * **검사는 둘이며 어느 쪽이든 부팅을 세운다.**
 *
 * 하나는 종전의 빈 값 검사다. 조용히 넘어가면 잘못된 주소가 저장돼 나중에 저장된 값을 전부
 * 치환하는 것 말고는 고칠 방법이 없다.
 *
 * 다른 하나가 #200에서 더한 것 — **`*.r2.cloudflarestorage.com`은 공개 읽기 주소가 아니다.**
 * 그것은 SigV4 서명을 요구하는 S3 API 엔드포인트라 서명 없는 GET(브라우저의 `<img src>`)에
 * 언제나 401을 돌려준다. 즉 그 값이 들어오면 결과가 "비어 있는 것"과 같으므로 같이 취급한다.
 * 넣어야 하는 값은 버킷의 r2.dev 도메인이나 연결한 커스텀 도메인이다.
 *
 * 출석 인증사진(#137)의 **읽기**는 #200부터 이 주소를 쓰지 않는다(비공개 버킷 + 서명된 GET) —
 * 그래도 이 검사를 남기는 것은 행사 본문 이미지(#161)가 여전히 공개 주소를 본문에 굳히기
 * 때문이며, 그쪽이 같은 함정을 두 번 밟지 않게 하는 것이 이 클래스의 남은 일이다.
 */
@Component
public class R2PublicBaseUrl {

    private static final String S3_API_HOST_SUFFIX = ".r2.cloudflarestorage.com";

    private final String baseUrl;

    public R2PublicBaseUrl(@Value("${r2.public-base-url}") String publicBaseUrl) {
        if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
            throw new IllegalStateException(
                    "r2.public-base-url 이 비어 있습니다 — 공개 이미지 URL을 조립할 수 없습니다.");
        }
        String trimmed = publicBaseUrl.trim();
        if (trimmed.contains(S3_API_HOST_SUFFIX)) {
            throw new IllegalStateException(
                    "r2.public-base-url 에 S3 API 엔드포인트("
                            + S3_API_HOST_SUFFIX
                            + ")가 들어 있습니다"
                            + " — 그 주소는 서명 없는 GET에 401을 돌려주므로 공개 읽기 주소가 될 수"
                            + " 없습니다. 버킷의 r2.dev 도메인이나 연결한 커스텀 도메인을 넣으세요.");
        }
        // 끝의 슬래시를 떼어 조립한 주소에 `//`가 생기지 않게 한다
        this.baseUrl = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    /** 오브젝트 키를 공개 읽기 주소로 조립한다 */
    public String urlOf(String objectKey) {
        return baseUrl + "/" + objectKey;
    }
}
