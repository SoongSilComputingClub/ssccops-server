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
 * **검사는 둘이며 어느 쪽이든 주소 조립을 거절한다.**
 *
 * 하나는 종전의 빈 값 검사다. 조용히 넘어가면 잘못된 주소가 저장돼 나중에 저장된 값을 전부
 * 치환하는 것 말고는 고칠 방법이 없다.
 *
 * 다른 하나가 #200에서 더한 것 — **`*.r2.cloudflarestorage.com`은 공개 읽기 주소가 아니다.**
 * 그것은 SigV4 서명을 요구하는 S3 API 엔드포인트라 서명 없는 GET(브라우저의 `<img src>`)에
 * 언제나 401을 돌려준다. 즉 그 값이 들어오면 결과가 "비어 있는 것"과 같으므로 같이 취급한다.
 * 넣어야 하는 값은 버킷의 r2.dev 도메인이나 연결한 커스텀 도메인이다.
 *
 * **거절하는 시점은 부팅이 아니라 조립할 때다.** 처음에는 생성자에서 던져 부팅을 세웠는데,
 * 그러면 이 값을 **쓰지도 않는** 학술 인증사진(#137·#200 — 비공개 버킷 + 서명된 URL이라 계정
 * 엔드포인트와 키만으로 업로드도 조회도 성립한다)까지 통째로 뜨지 못한다. 지키려던 것은 "잘못된
 * 주소가 어딘가에 굳는 것"이고 그것은 조립을 거절하는 것으로 충분하다 — 요청이 실패하므로 저장될
 * 값이 애초에 만들어지지 않는다. 대신 잘못된 설정은 부팅이 아니라 첫 행사 이미지 발급에서
 * 드러나므로 메시지에 무엇을 넣어야 하는지까지 적어 둔다.
 *
 * 그래서 이 클래스의 남은 사용처는 행사 본문 이미지(#161) 하나다 — 그쪽은 publicUrl을 본문
 * 마크다운에 문자열로 굳히므로 이 검사가 실제로 지키는 것이 있다.
 */
@Component
public class R2PublicBaseUrl {

    private static final String S3_API_HOST_SUFFIX = ".r2.cloudflarestorage.com";

    private final String configuredValue;

    public R2PublicBaseUrl(@Value("${r2.public-base-url:}") String publicBaseUrl) {
        this.configuredValue = publicBaseUrl == null ? "" : publicBaseUrl.trim();
    }

    /** 오브젝트 키를 공개 읽기 주소로 조립한다. 값이 공개 주소가 아니면 여기서 끊는다 */
    public String urlOf(String objectKey) {
        return baseUrl() + "/" + objectKey;
    }

    private String baseUrl() {
        if (configuredValue.isBlank()) {
            throw new IllegalStateException(
                    "r2.public-base-url 이 비어 있습니다 — 공개 이미지 URL을 조립할 수 없습니다."
                            + " 버킷의 r2.dev 도메인이나 연결한 커스텀 도메인을 넣으세요.");
        }
        if (configuredValue.contains(S3_API_HOST_SUFFIX)) {
            throw new IllegalStateException(
                    "r2.public-base-url 에 S3 API 엔드포인트("
                            + S3_API_HOST_SUFFIX
                            + ")가 들어 있습니다"
                            + " — 그 주소는 서명 없는 GET에 401을 돌려주므로 공개 읽기 주소가 될 수"
                            + " 없습니다. 버킷의 r2.dev 도메인이나 연결한 커스텀 도메인을 넣으세요.");
        }
        // 끝의 슬래시를 떼어 조립한 주소에 `//`가 생기지 않게 한다
        return configuredValue.endsWith("/")
                ? configuredValue.substring(0, configuredValue.length() - 1)
                : configuredValue;
    }
}
