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

    /** 업로드 URL의 유효기간(초) */
    public long uploadUrlTtlSeconds() {
        return UPLOAD_URL_TTL.toSeconds();
    }

    /*
     * 업로드 허가. **contentType까지 서명에 넣으므로 웹은 같은 Content-Type 헤더로 PUT 해야
     * 한다** — 서명에서 빼면 허가받은 URL로 아무 형식이나 올릴 수 있어 확장자 검사가
     * 무의미해진다. 그래서 부르는 쪽은 서명에 쓴 값을 그대로 응답에 실어 주고, 웹은 파일에서
     * 다시 읽지 않는다(브라우저의 File.type은 비거나 비표준일 수 있다 · ssccops#157).
     */
    public String presignPut(String objectKey, String contentType) {
        PutObjectRequest putObjectRequest =
                PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key(objectKey)
                        .contentType(contentType)
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
