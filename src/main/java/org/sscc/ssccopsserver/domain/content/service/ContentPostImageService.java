package org.sscc.ssccopsserver.domain.content.service;

import java.util.List;

import org.sscc.ssccopsserver.domain.content.dto.ContentImageResponse;
import org.sscc.ssccopsserver.domain.content.dto.ContentImageUploadRequest;
import org.sscc.ssccopsserver.domain.content.dto.ContentImageUploadResponse;

/*
 * 포스트 갤러리 (ssccops#381). 발급·삭제는 CONTENT_MANAGE 뒤(컨트롤러)이고, 목록·주소 조립은
 * 어드민·익명 응답 양쪽이 쓴다 — 누구에게 보여 줄지는 부르는 쪽이 이미 정했다.
 */
public interface ContentPostImageService {

    ContentImageUploadResponse issueUploadUrl(Long postId, ContentImageUploadRequest request);

    void deleteImage(Long postId, Long fileId);

    /** 발급 순서대로. 실물이 올라왔는지는 모른다(발급형 업로드 — 행이 곧 갤러리다) */
    List<ContentImageResponse> galleryOf(Long postId);

    /** 표지 등 한 장의 영구 읽기 주소. fileId가 null이면 null */
    String imageUrlOf(Long postId, Long fileId);

    /** 이 포스트의 갤러리에 있는 파일인가 — 표지 지정의 검증 재료 */
    boolean belongsToGallery(Long postId, Long fileId);

    /** 익명 리다이렉트용 서명 URL. 게시 여부는 부르는 쪽(PublicContentService)이 먼저 본다 */
    String viewUrlOf(Long postId, Long fileId);

    long viewRedirectCacheMaxAgeSeconds();
}
