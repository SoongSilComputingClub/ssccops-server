package org.sscc.ssccopsserver.domain.event.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.sscc.ssccopsserver.domain.event.code.error.EventErrorCode;
import org.sscc.ssccopsserver.domain.event.dto.EventImageUploadRequest;
import org.sscc.ssccopsserver.domain.event.repository.EventRepository;
import org.sscc.ssccopsserver.domain.file.service.FilePresigner;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;
import org.sscc.ssccopsserver.global.config.AppPublicBaseUrl;

/*
 * 발급·읽기 두 경로에서 **서명을 만들기 전에 무엇을 확인하는가**를 못 박는다 (#161 · #208).
 *
 * 스프링 컨텍스트를 띄우지 않는 것은 여기서 확인하려는 것이 순서와 거절이라, 협력자를 목으로
 * 두는 편이 "무엇이 호출되지 않았는가"를 더 곧게 드러내기 때문이다. 계약(상태 코드·응답 필드)은
 * EventImageControllerTest·PublicEventImageControllerTest가 본다.
 */
class EventImageServiceImplTest {

    private final EventRepository eventRepository = mock(EventRepository.class);
    private final FilePresigner filePresigner = mock(FilePresigner.class);
    private final PublicEventService publicEventService = mock(PublicEventService.class);

    /*
     * **app.public-base-url이 빈 경우는 여기서 보지 않는다** (#216). 그 값이 없는 서버는 아예
     * 뜨지 않으므로(AppPublicBaseUrl 생성자) 발급 경로가 빈 값을 만날 수 없다 — 도달할 수 없는
     * 상황을 여기서 검증하면 그 테스트가 곧 "부팅해도 된다"는 잘못된 약속이 된다.
     * 부팅이 실패하는 것 자체는 AppPublicBaseUrlTest가 본다.
     */

    /*
     * 형식 판정은 **서명보다 먼저다** (#210). 확장자 하나로 끝나므로 여기서 거절되면 업로드
     * 허가가 아예 만들어지지 않는다 — 서명을 먼저 만들고 나중에 거르면 응답이 실패해도 그 URL은
     * 유효해서 아무도 참조하지 않는 오브젝트가 버킷에 남는다.
     *
     * 정규화하고도 빈 문자열이 되는 값(`.`)을 쓰는 것은, 그것이 예전 계약에서 "확장자가 없는
     * 파일명"이 오던 자리이기 때문이다.
     */
    @Test
    void unknownFileExtensionNeverReachesTheSigner() {
        when(eventRepository.existsByIdAndDeletedAtIsNull(1L)).thenReturn(true);
        EventImageServiceImpl service = service(new AppPublicBaseUrl("https://api.sscc.club"));

        EventImageUploadRequest request = new EventImageUploadRequest(".", 1024L);

        assertThatThrownBy(() -> service.issueUploadUrl(1L, request))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(EventErrorCode.UNSUPPORTED_IMAGE_TYPE);

        verifyNoInteractions(filePresigner);
    }

    /*
     * 읽기의 순서 — **파일명이 먼저다.** 그 값은 오브젝트 키의 일부이고 버킷에는 학술 출석
     * 인증사진이 같이 들어 있으므로(ssccops#156), 형태가 어긋나면 행사를 조회하기도 전에 끊는다.
     */
    @Test
    void malformedFileNameIsRejectedBeforeAnythingElse() {
        EventImageServiceImpl service = service(new AppPublicBaseUrl("https://api.sscc.club"));

        assertThatThrownBy(() -> service.viewUrlOf(1L, "../academic-programs/1/face.png"))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(EventErrorCode.EVENT_IMAGE_NOT_FOUND);

        verifyNoInteractions(publicEventService);
        verifyNoInteractions(filePresigner);
    }

    /*
     * 그 다음이 게시 여부이고, **서명은 마지막이다.** 발급이 곧 읽기 권한이라 만들어 두고 응답에서
     * 빼는 구조는 한 줄만 어긋나도 그대로 새어 나간다(SessionFileReferenceViewer와 같은 태도).
     */
    @Test
    void unpublishedEventNeverReachesTheSigner() {
        EventImageServiceImpl service = service(new AppPublicBaseUrl("https://api.sscc.club"));
        doThrow(new GeneralException(EventErrorCode.EVENT_NOT_FOUND))
                .when(publicEventService)
                .requirePublishedEvent(any());

        String fileName = UUID.randomUUID() + ".png";

        assertThatThrownBy(() -> service.viewUrlOf(1L, fileName))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(EventErrorCode.EVENT_NOT_FOUND);

        verifyNoInteractions(filePresigner);
    }

    private EventImageServiceImpl service(AppPublicBaseUrl appPublicBaseUrl) {
        return new EventImageServiceImpl(
                eventRepository, filePresigner, appPublicBaseUrl, publicEventService);
    }
}
