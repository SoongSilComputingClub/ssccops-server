package org.sscc.ssccopsserver.domain.event.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.event.code.error.EventErrorCode;
import org.sscc.ssccopsserver.domain.event.dto.EventImageUploadRequest;
import org.sscc.ssccopsserver.domain.event.dto.EventImageUploadResponse;
import org.sscc.ssccopsserver.domain.event.repository.EventRepository;
import org.sscc.ssccopsserver.domain.file.code.ImageFileType;
import org.sscc.ssccopsserver.domain.file.service.FilePresigner;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;
import org.sscc.ssccopsserver.global.config.AppPublicBaseUrl;

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

    private final EventRepository eventRepository;

    /*
     * 서명은 파일 도메인이 만든다 (#220). 유효기간(업로드 10분 · 읽기 15분)과 그 근거도 그
     * 클래스로 함께 옮겼다 — 학술 인증사진과 같은 값·같은 이유였는데 두 도메인이 각자 상수로
     * 들고 있었고, 그런 값이 갈리면 그 실패는 서버 로그가 아니라 브라우저에서만 보인다.
     */
    private final FilePresigner filePresigner;

    /*
     * 읽기 경로가 "이 행사가 익명에게 보이는가"를 묻는 자리 (#208). 판정을 여기서 새로 세우지
     * 않는 것이 요점이다 — 공개 상세와 같은 기준을 써야 상세는 404인데 포스터만 열리는 상태가
     * 생기지 않는다(#187의 학술 event 접수 종료 판정이 대표적이다).
     */
    private final PublicEventService publicEventService;

    /*
     * 본문 마크다운에 박힐 읽기 주소의 호스트 (#208). 옛 R2PublicBaseUrl(버킷의 공개 도메인)이
     * 있던 자리이며, 버킷을 비공개로 유지하기로 하면서 조립해야 하는 값이 **우리 API의 주소**로
     * 바뀌었다 — 이미지는 이제 우리 도메인의 리다이렉트 엔드포인트로 읽힌다.
     *
     * 여기가 그 검사의 실질적인 수혜자인 것은 그대로다: 이 서비스가 만든 imageUrl은 행사 본문
     * 마크다운에 문자열로 굳어, 잘못된 값이 들어가면 이미 저장된 본문을 전부 치환하는 것 말고는
     * 고칠 방법이 없다(#200에서 실제로 그렇게 됐다).
     */
    private final AppPublicBaseUrl appPublicBaseUrl;

    public EventImageServiceImpl(
            EventRepository eventRepository,
            FilePresigner filePresigner,
            AppPublicBaseUrl appPublicBaseUrl,
            PublicEventService publicEventService) {
        this.eventRepository = eventRepository;
        this.filePresigner = filePresigner;
        this.appPublicBaseUrl = appPublicBaseUrl;
        this.publicEventService = publicEventService;
    }

    @Override
    public EventImageUploadResponse issueUploadUrl(Long eventId, EventImageUploadRequest request) {
        // 남의 행사도 아니고 아예 없는 행사에 키를 발급하지 않는다 — 경로의 행사가 먼저다
        if (!eventRepository.existsById(eventId)) {
            throw new GeneralException(EventErrorCode.EVENT_NOT_FOUND);
        }

        ImageFileType imageType = resolveImageType(request);

        /*
         * 크기 상한(10MB). **서버가 바이트를 보지 않으므로 이것은 요청이 신고한 크기에 대한
         * 판정이다** — 거짓으로 신고하면 이 검사는 통과한다. 여기서 끊는 이유는 업로드를
         * 시작하기 전에 화면이 안내할 수 있게 하기 위해서다.
         *
         * **실제 강제는 서명이 한다** (ssccops#188). 신고한 크기가 그대로 Content-Length로
         * 서명에 들어가므로, 거짓으로 신고하면 발급은 되지만 R2가 그 PUT을 거절한다. 상한 값
         * 자체를 여기 상수로 들고 있지 않은 것은 그래서다 — 강제하는 값과 안내하는 값이
         * 갈릴 수 있고, 실제로 학술 인증사진에는 그 상수가 아예 없어 안내조차 없었다.
         */
        if (request.fileSize() > filePresigner.maxUploadSizeBytes()) {
            throw new GeneralException(EventErrorCode.IMAGE_TOO_LARGE);
        }

        /*
         * 키 규칙 events/{eventId}/{uuid}.{ext} (D6). UUID라 같은 파일을 두 번 올려도 덮이지 않는다.
         *
         * **규칙을 여기 적지 않고 EventImageLocation에서 받아 온다** (#208). 읽기 경로가 요청의
         * 파일명으로 같은 키를 다시 조립하므로, 규칙이 두 벌이 되면 한쪽만 바뀌는 날 발급한
         * 주소가 아무것도 가리키지 않는다.
         */
        String fileName = EventImageLocation.newFileName(imageType);
        String objectKey = EventImageLocation.objectKeyOf(eventId, fileName);

        /*
         * 본문에 박힐 값은 **우리 도메인의 영구 주소**다 — 서명한 것이 아니다. 서명은 만료되고
         * 이 문자열은 마크다운에 굳으므로, 여기에 서명 URL을 실으면 시간이 지난 본문이 통째로
         * 깨진다(#208 결정 1).
         *
         * **서명보다 먼저 조립한다.** app.public-base-url이 비면 여기서 요청이 실패하는데,
         * 순서를 뒤집으면 그 실패 전에 이미 업로드 허가(서명된 PUT URL)를 하나 내준 뒤가 된다 —
         * 응답이 실패해도 그 URL은 유효해서 아무도 참조하지 않는 오브젝트가 버킷에 남는다.
         */
        String imageUrl =
                appPublicBaseUrl.urlOf(EventImageLocation.publicPathOf(eventId, fileName));

        /*
         * contentType까지 서명에 넣으므로 웹은 **같은 Content-Type 헤더로** PUT 해야 한다.
         * 서명에서 빼면 허가받은 URL로 아무 형식이나 올릴 수 있어 위의 형식 검사가 무의미해진다.
         *
         * 그 '같은 값'을 웹이 짐작하지 않게 응답에도 싣는다 (#210) — 서명에 쓴 것은 이 표의
         * 값이고, 브라우저가 파일에서 읽는 값은 그와 다를 수 있다.
         *
         * **크기도 같은 자리에 들어간다** (ssccops#188). 넘기는 값은 위 413이 본 값 그대로여야
         * 한다 — 여기서 다시 계산하거나 상한값을 넘기면 안내와 강제가 서로 다른 숫자를 보게
         * 되고, 그 어긋남은 발급까지 성공한 뒤 R2의 403으로만 드러난다.
         */
        String uploadUrl =
                filePresigner.presignPut(objectKey, imageType.getContentType(), request.fileSize());

        /*
         * 서명에 쓴 contentType을 그대로 돌려준다 (#210). 웹이 파일에서 다시 읽으면
         * (`File.type`) 비거나 비표준인 값이 나와 서명과 어긋나고, 그 PUT은 R2에서 조용히
         * 거절된다 — 서버 로그에는 아무것도 남지 않는다(ssccops#157).
         */
        return new EventImageUploadResponse(
                uploadUrl,
                imageUrl,
                objectKey,
                imageType.getContentType(),
                filePresigner.uploadUrlTtlSeconds());
    }

    /*
     * 읽기 서명 (#208). **하는 일은 둘이며 순서가 있다 — 내줘도 되는지 먼저 정하고, 그 다음에
     * 서명한다.** 서명은 곧 읽기 권한이라 만들어 두고 나중에 거르는 구조는 한 줄만 어긋나도
     * 그대로 새어 나간다(SessionFileReferenceViewer와 같은 태도).
     *
     * 파일명을 먼저 보는 것은 그 값이 **오브젝트 키의 일부**이기 때문이다. 버킷에는 학술
     * 출석 인증사진이 같이 들어 있어(ssccops#156) `../`가 낀 파일명이 키가 되면 그것이 곧
     * 남의 얼굴 사진이다 — 형태가 어긋나면 행사를 조회하기도 전에 끊는다.
     *
     * **오브젝트가 실제로 있는지는 확인하지 않는다.** 서버가 PUT을 관측하지 않아 참조가 실물을
     * 가리킨다는 보장이 애초에 없고, 확인하려면 요청마다 HeadObject가 한 번 더 나간다. 없으면
     * R2가 404를 돌려주고 브라우저에는 깨진 이미지가 보인다.
     */
    @Override
    public String viewUrlOf(Long eventId, String fileName) {
        if (!EventImageLocation.isValidFileName(fileName)) {
            throw new GeneralException(EventErrorCode.EVENT_IMAGE_NOT_FOUND);
        }

        // 미게시 행사·없는 행사 모두 404 EVENT_NOT_FOUND — 공개 상세와 같은 판정을 그대로 쓴다
        publicEventService.requirePublishedEvent(eventId);

        return filePresigner.presignGet(EventImageLocation.objectKeyOf(eventId, fileName));
    }

    /*
     * 캐시 수명은 서명 수명에서 파생된다 (ssccops ADR-0010). 여기서 다시 계산하지 않고
     * 그대로 넘기는 것은 두 값이 어긋날 자리를 만들지 않기 위해서다.
     */
    @Override
    public long viewRedirectCacheMaxAgeSeconds() {
        return filePresigner.viewRedirectCacheMaxAgeSeconds();
    }

    /*
     * **확장자 하나로 형식을 정한다** (#210 · ssccops#157). 예전에는 요청이 실어 보낸
     * contentType과 파일명의 확장자를 둘 다 보고 서로 맞아야 통과시켰는데, 그 교차 검증은
     * 지킬 것을 지키지 못하면서 멀쩡한 업로드만 막았다 — 서버는 바이트를 보지 않으므로 어느
     * 쪽도 파일의 진짜 정체가 아니라 **요청이 한 신고**이고, `evil.html`을 `poster.png` ·
     * `image/png`로 신고하면 둘 다 맞아떨어져 그대로 통과한다. 반대로 브라우저가 채우는
     * `File.type`은 비거나(`""`) 비표준(`image/jpg`)일 수 있어, 진짜 PNG를 올리는 요청이
     * 400으로 튕겼다.
     *
     * 그래서 판정에 쓰는 값을 하나로 줄인다 — 어긋날 값이 하나뿐이면 어긋날 수 없다. 형식은
     * 서버가 이 표에서 끌어와 서명과 응답에 함께 쓰고, 웹은 그 값을 헤더에 옮겨 적기만 한다
     * (학술 인증사진 SessionFileReferenceServiceImpl과 같은 모양). 허용 목록 자체는
     * ImageFileType이 갖는다(형식을 늘리는 자리를 한 곳으로 묶는다).
     */
    private ImageFileType resolveImageType(EventImageUploadRequest request) {
        return ImageFileType.ofFileExtension(request.normalizedFileExt())
                .orElseThrow(() -> new GeneralException(EventErrorCode.UNSUPPORTED_IMAGE_TYPE));
    }
}
