package org.sscc.ssccopsserver.domain.file.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/*
 * 서명 자체가 지키는 두 규칙 (#638) — 업로드 크기 상한과 내려받기 이름의 인코딩.
 *
 * 프리사이너를 목으로 두지 않고 R2Config와 같은 설정의 진짜 S3Presigner를 쓴다. 서명은 로컬
 * 계산이라 네트워크가 필요 없고, 확인할 것이 «SDK에 무엇을 넘겼나»가 아니라 «URL에 무엇이
 * 실려 나가나»이기 때문이다.
 */
class FilePresignerTest {

    private static final String BUCKET = "test-bucket";
    private static final long CAP = 10L * 1024 * 1024;

    private static final S3Presigner PRESIGNER =
            S3Presigner.builder()
                    .endpointOverride(URI.create("https://account.r2.cloudflarestorage.com"))
                    .region(Region.of("auto"))
                    .credentialsProvider(
                            StaticCredentialsProvider.create(
                                    AwsBasicCredentials.create("access", "secret")))
                    .serviceConfiguration(
                            S3Configuration.builder().pathStyleAccessEnabled(true).build())
                    .build();

    private final FilePresigner filePresigner = new FilePresigner(PRESIGNER, BUCKET);

    @AfterAll
    static void closePresigner() {
        PRESIGNER.close();
    }

    /*
     * **상한을 넘는 크기는 서명하지 않는다.** 서명은 «신고한 크기 = 실제 크기»만 강제하므로,
     * 부르는 쪽이 413 판정을 빠뜨리면 정직하게 200MB를 신고한 업로드가 그대로 허가됐다.
     */
    @Test
    void refusesToSignAnUploadLargerThanTheCap() {
        assertThatThrownBy(
                        () ->
                                filePresigner.presignPut(
                                        "operations/1/a.pdf", "application/pdf", CAP + 1, CAP))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /* 상한과 같은 크기는 허가한다 — 경계는 «넘으면»이다. 크기는 그대로 서명 헤더에 들어간다 */
    @Test
    void signsAnUploadAtTheCapWithContentLength() {
        String url = filePresigner.presignPut("operations/1/a.pdf", "application/pdf", CAP, CAP);

        assertThat(url).contains("/" + BUCKET + "/operations/1/a.pdf");
        assertThat(queryParam(url, "X-Amz-SignedHeaders")).contains("content-length");
    }

    /* 상한은 용도마다 다르다 — 이미지 상한을 넘는 25MB 첨부도 그 상한을 받으면 허가된다 */
    @Test
    void capIsPerCall() {
        long attachmentCap = 25L * 1024 * 1024;

        String url =
                filePresigner.presignPut(
                        "operations/1/deck.pptx",
                        "application/vnd.ms-powerpoint",
                        CAP + 1,
                        attachmentCap);

        assertThat(url).contains("operations/1/deck.pptx");
    }

    @Test
    void downloadNameIsUtf8PercentEncoded() {
        assertThat(contentDispositionOf("회의록.pdf"))
                .isEqualTo("attachment; filename*=UTF-8''%ED%9A%8C%EC%9D%98%EB%A1%9D.pdf");
    }

    /*
     * 공백은 `%20`이다. URLEncoder는 `+`로 적는데 RFC 5987의 attr-char에서 `+`는 글자 그대로라
     * 받는 쪽이 공백으로 되돌리지 않는다. 원래 `+`인 글자는 `%2B`로 남아야 한다.
     */
    @Test
    void spaceIsPercent20AndPlusStaysPlus() {
        assertThat(contentDispositionOf("2학기 예산 C++.xlsx"))
                .isEqualTo(
                        "attachment; filename*=UTF-8''2%ED%95%99%EA%B8%B0%20%EC%98%88%EC%82%B0"
                                + "%20C%2B%2B.xlsx");
    }

    /* `*`는 attr-char에 없다 — URLEncoder가 남기는 것을 `%2A`로 고친다 */
    @Test
    void asteriskIsEncoded() {
        String disposition = contentDispositionOf("중요*.txt");

        assertThat(disposition).isEqualTo("attachment; filename*=UTF-8''%EC%A4%91%EC%9A%94%2A.txt");
        assertThat(disposition.substring("attachment; filename*=".length())).doesNotContain("*");
    }

    /* 이름이 없으면 내려받기 이름을 싣지 않는다 — 목록·미리보기 읽기와 같은 서명이다 */
    @Test
    void noDispositionWithoutName() {
        assertThat(filePresigner.presignGet("operations/1/a.pdf", " "))
                .doesNotContain("response-content-disposition");
        assertThat(filePresigner.presignGet("operations/1/a.pdf"))
                .doesNotContain("response-content-disposition");
    }

    /* 서명 URL의 쿼리에 실린 response-content-disposition — 헤더로 돌아올 값 그대로 */
    private String contentDispositionOf(String downloadFileName) {
        String url = filePresigner.presignGet("operations/1/a.pdf", downloadFileName);
        return queryParam(url, "response-content-disposition");
    }

    private static String queryParam(String url, String name) {
        String query = URI.create(url).getRawQuery();
        return Arrays.stream(query.split("&"))
                .filter(pair -> pair.startsWith(name + "="))
                .map(
                        pair ->
                                URLDecoder.decode(
                                        pair.substring(name.length() + 1), StandardCharsets.UTF_8))
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + " 쿼리가 없다: " + url));
    }
}
