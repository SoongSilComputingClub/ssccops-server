package org.sscc.ssccopsserver.domain.file.service;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/*
 * R2 오브젝트에 대한 서명된 URL을 만드는 유일한 자리 (#220 · #137·#161·#200·#208이 세운 구조).
 *
 * **서버는 파일 바이트를 만지지 않는다.** 하는 일은 "이 키에 이 형식을 올려도 좋다" 또는
 * "이 키를 읽어도 좋다"는 허가를 짧은 유효기간의 URL로 내주는 것뿐이고, 바이트는 브라우저와
 * R2 사이에서 직접 오간다. 멀티파트 업로드를 만들지 않는 것은 취향이 아니라 배포 환경의
 * 제약이다 — 서버가 버퍼링하면 동시 업로드가 몰릴 때 메모리가 요청 수에 비례해 늘고, 512MB
 * 컨테이너에서 실제로 그 일이 있었다(#107).
 *
 * **이 클래스는 누구에게 내주는지 묻지 않는다.** 판정은 부르는 쪽이 이미 끝냈어야 하고, 그
 * 순서가 뒤집히면(서명해 두고 응답에서 거른다) 한 줄만 어긋나도 그대로 새어 나간다
 * (SessionFileReferenceViewer · EventImageServiceImpl 주석). 여기 모은 것은 그 판정이 아니라
 * **서명 자체**다 — 학술과 행사가 각자 서명하던 동안 TTL·버킷·프리사이너가 갈릴 자리가
 * 네 곳이었고, 그 어긋남은 서버 로그가 아니라 브라우저에서만 보인다.
 *
 * **오브젝트가 실제로 있는지는 확인하지 않는다.** 서버가 PUT을 관측하지 않아 참조가 실물을
 * 가리킨다는 보장이 애초에 없고, 확인하려면 조회마다 HeadObject가 한 번씩 더 나간다. 없으면
 * R2가 404를 돌려주고 화면은 다시 올린다.
 */
@Component
public class FilePresigner {

    /*
     * presigned PUT URL의 유효기간. **이 URL은 그 자체로 남의 버킷에 쓸 수 있는 권한**이라
     * 짧아야 한다 — 파일을 고른 직후 한 번 올리는 데 필요한 시간(느린 회선에서 큰 이미지 한
     * 장)이면 충분하고, 길게 두면 어딘가에 새어 나간 URL이 그만큼 오래 살아 있다.
     */
    private static final Duration UPLOAD_URL_TTL = Duration.ofMinutes(10);

    /*
     * 업로드 크기 상한 (ssccops#188). **서명하는 쪽이 갖는다** — 상한을 강제하는 유일한 지점이
     * presignPut이기 때문이다. 그전에는 이 숫자가 EventImageServiceImpl에만 있었고 학술 쪽에는
     * 아예 없어, 인증사진은 안내조차 없이 아무 크기나 올라갔다.
     *
     * 판정을 두 곳에서 하는 것은 뜻이 달라서다: 부르는 쪽의 413은 **올리기 전에 알려 주는
     * 안내**이고(요청이 신고한 크기라 거짓일 수 있다), 여기 서명은 **강제**다.
     */
    private static final long MAX_UPLOAD_SIZE_BYTES = 10L * 1024 * 1024;

    /*
     * 서명된 읽기 URL의 유효기간. 업로드보다 조금 길다 — 열어 둔 화면이 이미지를 다시 그리는
     * 데 쓰이고, 만료되면 그 화면이 상세를 다시 부르면 된다(그래서 남은 시간을 응답에 싣는다).
     *
     * 길게 두지 않는 이유는 업로드 URL과 같다: 이 URL은 그 자체로 오브젝트를 읽을 수 있는
     * 권한이라 새어 나가면 만료까지 유효하다.
     */
    private static final Duration VIEW_URL_TTL = Duration.ofMinutes(15);

    private final S3Presigner r2Presigner;
    private final String bucketName;

    public FilePresigner(S3Presigner r2Presigner, @Value("${r2.bucket-name}") String bucketName) {
        this.r2Presigner = r2Presigner;
        this.bucketName = bucketName;
    }

    /** 읽기 URL의 유효기간(초). 화면이 만료 전에 다시 받아 갈 수 있도록 응답에 싣는다 */
    public long viewUrlTtlSeconds() {
        return VIEW_URL_TTL.toSeconds();
    }

    /*
     * 서명된 읽기 URL로 **리다이렉트하는 응답**을 캐시해도 되는 시간(초). ssccops ADR-0010.
     *
     * **반드시 VIEW_URL_TTL보다 짧아야 한다.** 302를 돌려주는 엔드포인트의 주소는 만료되지
     * 않지만 그 Location에 실린 서명은 만료되므로, 캐시가 302를 서명보다 오래 들고 있으면
     * 이미 죽은 서명을 가리키는 리다이렉트가 재생돼 이미지가 깨진다. 캐시된 응답은 저장된
     * 직후부터 max-age 동안 재사용되는데 그 시작점이 서명이 만들어진 시점이므로, 여유를
     * 남겨 두면 그 구간이 통째로 서명 유효기간 안에 들어온다.
     *
     * **여기서 파생시키는 것이 요점이다.** 컨트롤러에 초를 박아 두면 VIEW_URL_TTL을 줄이는
     * 날 조용히 어긋나고, 그 어긋남은 배포가 아니라 며칠 뒤 깨진 이미지로 드러난다 —
     * 두 값이 한 파일에 있으면 한쪽만 고칠 수 없다.
     */
    public long viewRedirectCacheMaxAgeSeconds() {
        return VIEW_URL_TTL.multipliedBy(2).dividedBy(3).toSeconds();
    }

    /** 업로드 URL의 유효기간(초) */
    public long uploadUrlTtlSeconds() {
        return UPLOAD_URL_TTL.toSeconds();
    }

    /**
     * 업로드 크기 상한(바이트). 부르는 쪽이 발급 전에 안내용 413을 던지는 데 쓴다 — 실제 강제는 {@link #presignPut}이 서명에 넣는
     * Content-Length다.
     */
    public long maxUploadSizeBytes() {
        return MAX_UPLOAD_SIZE_BYTES;
    }

    /*
     * 업로드 허가. **contentType까지 서명에 넣으므로 웹은 같은 Content-Type 헤더로 PUT 해야
     * 한다** — 서명에서 빼면 허가받은 URL로 아무 형식이나 올릴 수 있어 확장자 검사가
     * 무의미해진다. 그래서 부르는 쪽은 서명에 쓴 값을 그대로 응답에 실어 주고, 웹은 파일에서
     * 다시 읽지 않는다(브라우저의 File.type은 비거나 비표준일 수 있다 · ssccops#157).
     *
     * **contentLength도 같은 이유로 서명에 들어간다** (ssccops#188). 크기 상한을 강제할 수
     * 있는 지점이 여기뿐이다 — PUT은 서버를 거치지 않으므로 발급 시점에 조건을 걸지 않으면
     * 강제할 자리가 아예 없고, 부르는 쪽의 413은 **요청이 신고한 크기**에 대한 판정이라
     * 100바이트라고 신고하고 200MB를 올리는 것을 막지 못했다.
     *
     * 서명 헤더에 실제로 들어가는 것은 실측으로 확인했다 —
     * `X-Amz-SignedHeaders=content-length;content-type;host`. 브라우저는 Content-Length를
     * 직접 설정할 수 없고 본문 크기에서 자동으로 채우므로, **신고한 크기와 실제 파일이 다르면
     * 서명이 맞지 않아 R2가 거절한다.** 그것이 이 서명이 노리는 것이다.
     *
     * `isBrowserExecutable`이 false로 내려오지만 그것은 **contentLength를 넣기 전에도 마찬가지**
     * 였다(content-type 하나만으로도 false다). host 밖의 서명 헤더가 있으면 붙는 표시일 뿐이라
     * 업로드 가능 여부의 신호가 아니다 — 그 상태로 dev 업로드가 계속 돌고 있었다.
     */
    public String presignPut(String objectKey, String contentType, long contentLength) {
        PutObjectRequest putObjectRequest =
                PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key(objectKey)
                        .contentType(contentType)
                        .contentLength(contentLength)
                        .build();

        return r2Presigner
                .presignPutObject(
                        PutObjectPresignRequest.builder()
                                .signatureDuration(UPLOAD_URL_TTL)
                                .putObjectRequest(putObjectRequest)
                                .build())
                .url()
                .toString();
    }

    /*
     * 읽기 허가. 업로드와 달리 contentType을 서명에 넣지 않는다 — 그쪽은 "이 형식만 올려도
     * 좋다"는 허가라 형식이 조건의 일부지만, 읽기는 이미 저장된 오브젝트를 그대로 내려받는
     * 것이라 조건에 넣을 것이 키뿐이다.
     */
    public String presignGet(String objectKey) {
        GetObjectRequest getObjectRequest =
                GetObjectRequest.builder().bucket(bucketName).key(objectKey).build();

        return r2Presigner
                .presignGetObject(
                        GetObjectPresignRequest.builder()
                                .signatureDuration(VIEW_URL_TTL)
                                .getObjectRequest(getObjectRequest)
                                .build())
                .url()
                .toString();
    }
}
