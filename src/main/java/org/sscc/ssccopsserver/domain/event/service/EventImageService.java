package org.sscc.ssccopsserver.domain.event.service;

import org.sscc.ssccopsserver.domain.event.dto.EventImageUploadRequest;
import org.sscc.ssccopsserver.domain.event.dto.EventImageUploadResponse;

/*
 * 행사 이미지의 발급과 읽기 (#161 · #208).
 *
 * 두 메서드가 같은 자리에 있는 것은 **오브젝트 키 규칙을 공유하기 때문이다**(EventImageLocation).
 * 발급이 만든 키를 읽기가 다시 조립하므로, 규칙이 갈리는 순간 발급한 주소가 아무것도 가리키지
 * 않는다.
 */
public interface EventImageService {

    EventImageUploadResponse issueUploadUrl(Long eventId, EventImageUploadRequest request);

    /*
     * 그 이미지를 지금 읽을 수 있는 서명된 R2 GET URL. 리다이렉트 엔드포인트가 Location에
     * 실을 값이며 **요청마다 새로 만든다** — 저장되는 것은 이 값이 아니라 우리 도메인의
     * 영구 주소다(EventImageUploadResponse.imageUrl).
     *
     * 미게시 행사·없는 행사·발급한 적 없는 형태의 파일명은 모두 404다.
     */
    String viewUrlOf(Long eventId, String fileName);
}
