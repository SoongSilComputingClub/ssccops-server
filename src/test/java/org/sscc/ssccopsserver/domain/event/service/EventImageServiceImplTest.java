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
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;
import org.sscc.ssccopsserver.global.config.AppPublicBaseUrl;

import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/*
 * 발급·읽기 두 경로에서 **서명을 만들기 전에 무엇을 확인하는가**를 못 박는다 (#161 · #208).
 *
 * 스프링 컨텍스트를 띄우지 않는 것은 여기서 확인하려는 것이 순서와 거절이라, 협력자를 목으로
 * 두는 편이 "무엇이 호출되지 않았는가"를 더 곧게 드러내기 때문이다. 계약(상태 코드·응답 필드)은
 * EventImageControllerTest·PublicEventImageControllerTest가 본다.
 */
class EventImageServiceImplTest {

    private final EventRepository eventRepository = mock(EventRepository.class);
    private final S3Presigner r2Presigner = mock(S3Presigner.class);
    private final PublicEventService publicEventService = mock(PublicEventService.class);

    /*
     * app.public-base-url이 비면 **발급이 거절된다**.
     *
     * 조용히 넘어가지 않는 이유는 이 값으로 만든 주소가 행사 본문 마크다운에 문자열로 굳기
     * 때문이다 — 잘못된 주소가 저장되면 본문을 전부 치환하는 것 말고는 고칠 방법이 없다(#200에서
     * 실제로 그렇게 됐다). 요청을 실패시키면 저장될 값이 애초에 만들어지지 않는다.
     */
    @Test
    void issuingIsRefusedWhenAppPublicBaseUrlIsBlank() {
        when(eventRepository.existsById(1L)).thenReturn(true);
        EventImageServiceImpl service = service(new AppPublicBaseUrl(""));

        assertThatThrownBy(
                        () ->
                                service.issueUploadUrl(
                                        1L,
                                        new EventImageUploadRequest(
                                                "poster.png", "image/png", 1024L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.public-base-url");
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
        verifyNoInteractions(r2Presigner);
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

        assertThatThrownBy(() -> service.viewUrlOf(1L, UUID.randomUUID() + ".png"))
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(EventErrorCode.EVENT_NOT_FOUND);

        verifyNoInteractions(r2Presigner);
    }

    private EventImageServiceImpl service(AppPublicBaseUrl appPublicBaseUrl) {
        return new EventImageServiceImpl(
                eventRepository, r2Presigner, "test-bucket", appPublicBaseUrl, publicEventService);
    }
}
