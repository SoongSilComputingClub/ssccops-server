package org.sscc.ssccopsserver.domain.event.service;

import java.time.Duration;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.event.code.EventImageType;
import org.sscc.ssccopsserver.domain.event.code.error.EventErrorCode;
import org.sscc.ssccopsserver.domain.event.dto.EventImageUploadRequest;
import org.sscc.ssccopsserver.domain.event.dto.EventImageUploadResponse;
import org.sscc.ssccopsserver.domain.event.repository.EventRepository;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/*
 * 행사 이미지 업로드 URL 발급 (#161 · wave2 D6).
 *
 * **이 서비스는 파일을 받지 않는다.** 하는 일은 "이 행사에 이 형식의 이미지를 하나 올려도
 * 좋다"는 허가를 짧은 유효기간의 서명된 URL로 내주는 것뿐이고, 실제 바이트는 운영 웹에서
 * R2로 직접 간다. 멀티파트 업로드 엔드포인트를 만들지 않는 것은 취향이 아니라 배포 환경의
 * 제약이다 — 512MB 컨테이너는 부팅 중 엔티티 메타모델을 만들다 죽은 전례가 있고(#107),
 * 그 위에 업로드 버퍼링을 얹으면 이미지 몇 장으로 프로세스가 죽는다.
 *
 * **DB에 아무것도 남기지 않는다.** 데이터사전에 파일 테이블이 없고, 본문 마크다운의 링크가
 * 곧 참조다. 그래서 이 서비스는 "실제로 올라왔는가"를 알지 못한다 — PUT이 서버를 거치지
 * 않으므로 관측할 수 있는 사건이 아니다. 발급했지만 쓰이지 않은 키는 그냥 존재하지 않는
 * 오브젝트이고, 올라왔지만 본문에서 지워진 오브젝트(고아)의 정리는 범위 밖이다(§7 운영 이슈).
 *
 * 트랜잭션이 readOnly인 것은 이 경로가 행사 존재 확인 한 번 말고는 DB를 건드리지 않기 때문이다.
 */
@Service
@Transactional(readOnly = true)
public class EventImageServiceImpl implements EventImageService {

    /*
     * presigned PUT URL의 유효기간. **이 URL은 그 자체로 남의 버킷에 쓸 수 있는 권한**이라
     * 짧아야 한다 — 운영자가 파일을 고른 직후 한 번 올리는 데 필요한 시간(느린 회선에서 큰
     * 이미지 한 장)이면 충분하고, 길게 두면 어딘가에 새어 나간 URL이 그만큼 오래 살아 있다.
     */
    private static final Duration UPLOAD_URL_TTL = Duration.ofMinutes(10);

    /*
     * 이미지 한 장의 크기 상한(10MB). 행사 본문에 붙는 삽화·포스터를 기준으로 잡았다.
     *
     * **서버가 바이트를 보지 않으므로 이것은 요청이 신고한 크기에 대한 판정이다** — 거짓으로
     * 신고하면 그대로 통과한다. 실제 강제는 버킷/도메인 정책의 몫이며, 여기서 끊는 이유는
     * 업로드를 시작하기 전에 화면이 안내할 수 있게 하기 위해서다.
     */
    private static final long MAX_IMAGE_SIZE_BYTES = 10L * 1024 * 1024;

    private final EventRepository eventRepository;
    private final S3Presigner r2Presigner;
    private final String bucketName;
    private final String publicBaseUrl;

    /*
     * publicBaseUrl에 기본값을 두지 않는다 — 값이 없으면 **부팅이 실패한다**. 조용히 빈 값으로
     * 넘어가면 잘못된 publicUrl이 본문 마크다운에 문자열로 굳어 버리고, 그때는 이미 저장된
     * 본문을 전부 치환하는 것 말고 고칠 방법이 없다.
     */
    public EventImageServiceImpl(
            EventRepository eventRepository,
            S3Presigner r2Presigner,
            @Value("${r2.bucket-name}") String bucketName,
            @Value("${r2.public-base-url}") String publicBaseUrl) {
        this.eventRepository = eventRepository;
        this.r2Presigner = r2Presigner;
        this.bucketName = bucketName;
        this.publicBaseUrl = normalizeBaseUrl(publicBaseUrl);
    }

    @Override
    public EventImageUploadResponse issueUploadUrl(Long eventId, EventImageUploadRequest request) {
        // 남의 행사도 아니고 아예 없는 행사에 키를 발급하지 않는다 — 경로의 행사가 먼저다
        if (!eventRepository.existsById(eventId)) {
            throw new GeneralException(EventErrorCode.EVENT_NOT_FOUND);
        }

        EventImageType imageType = resolveImageType(request);
        if (request.fileSize() > MAX_IMAGE_SIZE_BYTES) {
            throw new GeneralException(EventErrorCode.IMAGE_TOO_LARGE);
        }

        // 키 규칙 events/{eventId}/{uuid}.{ext} (D6). UUID라 같은 파일을 두 번 올려도 덮이지 않는다
        String objectKey =
                "events/%d/%s.%s".formatted(eventId, UUID.randomUUID(), imageType.getExtension());

        /*
         * contentType까지 서명에 넣으므로 웹은 **같은 Content-Type 헤더로** PUT 해야 한다.
         * 서명에서 빼면 허가받은 URL로 아무 형식이나 올릴 수 있어 위의 형식 검사가 무의미해진다.
         */
        PutObjectRequest putObjectRequest =
                PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key(objectKey)
                        .contentType(imageType.getContentType())
                        .build();

        String uploadUrl =
                r2Presigner
                        .presignPutObject(
                                PutObjectPresignRequest.builder()
                                        .signatureDuration(UPLOAD_URL_TTL)
                                        .putObjectRequest(putObjectRequest)
                                        .build())
                        .url()
                        .toString();

        return new EventImageUploadResponse(
                uploadUrl, publicBaseUrl + "/" + objectKey, objectKey, UPLOAD_URL_TTL.toSeconds());
    }

    /*
     * contentType과 확장자를 둘 다 보고 서로 맞아야 통과시킨다 — 한쪽만 보면 `evil.html`을
     * image/png라고 주장하거나 그 반대로 통과시킬 수 있다. 허용 목록 자체는 EventImageType이
     * 갖는다(형식을 늘리는 자리를 한 곳으로 묶는다).
     */
    private EventImageType resolveImageType(EventImageUploadRequest request) {
        EventImageType imageType =
                EventImageType.ofContentType(request.contentType())
                        .orElseThrow(
                                () -> new GeneralException(EventErrorCode.UNSUPPORTED_IMAGE_TYPE));
        if (!imageType.matchesExtension(request.fileExtension())) {
            throw new GeneralException(EventErrorCode.UNSUPPORTED_IMAGE_TYPE);
        }
        return imageType;
    }

    /* 끝의 슬래시를 떼어 publicUrl에 `//`가 생기지 않게 한다. 비어 있으면 부팅을 세운다 */
    private static String normalizeBaseUrl(String publicBaseUrl) {
        if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
            throw new IllegalStateException(
                    "r2.public-base-url 이 비어 있습니다 — 공개 이미지 URL을 조립할 수 없습니다.");
        }
        String trimmed = publicBaseUrl.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
