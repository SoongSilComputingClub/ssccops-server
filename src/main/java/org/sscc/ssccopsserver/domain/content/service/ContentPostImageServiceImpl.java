package org.sscc.ssccopsserver.domain.content.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.content.code.error.ContentErrorCode;
import org.sscc.ssccopsserver.domain.content.dto.ContentImageResponse;
import org.sscc.ssccopsserver.domain.content.dto.ContentImageUploadRequest;
import org.sscc.ssccopsserver.domain.content.dto.ContentImageUploadResponse;
import org.sscc.ssccopsserver.domain.content.entity.ContentPostEntity;
import org.sscc.ssccopsserver.domain.content.repository.ContentPostRepository;
import org.sscc.ssccopsserver.domain.file.code.FileTargetType;
import org.sscc.ssccopsserver.domain.file.code.ImageFileType;
import org.sscc.ssccopsserver.domain.file.entity.FileReferenceEntity;
import org.sscc.ssccopsserver.domain.file.service.FilePresigner;
import org.sscc.ssccopsserver.domain.file.service.FileReferenceService;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;
import org.sscc.ssccopsserver.global.config.AppPublicBaseUrl;

import lombok.RequiredArgsConstructor;

/*
 * 포스트 갤러리 (ssccops#381). 행사 본문 이미지(#161·#208)의 구조 — presigned PUT 발급 · 서버는
 * 바이트를 다루지 않는다 · 읽기는 우리 도메인의 영구 리다이렉트 주소 — 를 그대로 쓰되,
 * **file_rfrnc 행을 남긴다**는 점이 다르다(FileTargetType.CONTENT_POST 주석). 갤러리는 본문
 * 링크가 아니라 «몇 장이 무엇인가»를 서버가 답해야 하는 목록이고 표지가 그 행을 FK로 가리킨다.
 *
 * **행은 발급 순간 생긴다.** PUT이 서버를 지나지 않으므로 실제로 올라왔는지는 여전히 모른다 —
 * 발급받고 올리지 않은 장은 갤러리에 빈 칸으로 남고, 정리는 운영진의 삭제(DELETE)다. «업로드
 * 완료 확인» API를 두는 안은 기각했다 — 서버가 R2에 HEAD를 치는 왕복이 늘고, 확인을 빠뜨린
 * 클라이언트(스크립트·MCP)의 사진이 영영 갤러리에 안 뜬다. 빈 칸이 보이는 쪽이 낫다.
 *
 * 크기 상한·확장자 규칙·contentType을 서버가 정하는 계약은 행사와 같다(#210).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ContentPostImageServiceImpl implements ContentPostImageService {

    private final ContentPostRepository postRepository;
    private final FileReferenceService fileReferenceService;
    private final FilePresigner filePresigner;
    private final AppPublicBaseUrl appPublicBaseUrl;

    @Override
    @Transactional
    public ContentImageUploadResponse issueUploadUrl(
            Long postId, ContentImageUploadRequest request) {
        if (!postRepository.existsById(postId)) {
            throw new GeneralException(ContentErrorCode.POST_NOT_FOUND);
        }
        ImageFileType imageType =
                ImageFileType.ofFileExtension(request.normalizedFileExt())
                        .orElseThrow(
                                () ->
                                        new GeneralException(
                                                ContentErrorCode.UNSUPPORTED_IMAGE_TYPE));
        if (request.fileSize() > filePresigner.maxUploadSizeBytes()) {
            throw new GeneralException(ContentErrorCode.IMAGE_TOO_LARGE);
        }

        String objectKey = ContentImageLocation.newObjectKey(postId, imageType);
        FileReferenceEntity reference =
                fileReferenceService.add(FileTargetType.CONTENT_POST, postId, objectKey);

        String uploadUrl =
                filePresigner.presignPut(objectKey, imageType.getContentType(), request.fileSize());
        return new ContentImageUploadResponse(
                reference.getId(),
                uploadUrl,
                imageUrlOf(postId, reference.getId()),
                objectKey,
                imageType.getContentType(),
                filePresigner.uploadUrlTtlSeconds());
    }

    /*
     * 삭제. 그 장이 표지였으면 표지를 먼저 비운다 — cover_file_id가 FK라 순서가 반대면 위반이다.
     * 오브젝트는 FileReferenceService가 커밋 뒤에 지운다(잘못된 데이터보다 고아가 낫다).
     * 없는 파일·남의 포스트의 파일은 같은 404다(대상 조건을 함께 건 조회).
     */
    @Override
    @Transactional
    public void deleteImage(Long postId, Long fileId) {
        ContentPostEntity post =
                postRepository
                        .findById(postId)
                        .orElseThrow(() -> new GeneralException(ContentErrorCode.POST_NOT_FOUND));
        if (!belongsToGallery(postId, fileId)) {
            throw new GeneralException(ContentErrorCode.CONTENT_IMAGE_NOT_FOUND);
        }
        post.clearCoverIfMatches(fileId);
        postRepository.flush();
        fileReferenceService.deleteOneOfTarget(FileTargetType.CONTENT_POST, postId, fileId);
    }

    @Override
    public List<ContentImageResponse> galleryOf(Long postId) {
        return fileReferenceService.findAllByTarget(FileTargetType.CONTENT_POST, postId).stream()
                .map(
                        reference ->
                                new ContentImageResponse(
                                        reference.getId(), imageUrlOf(postId, reference.getId())))
                .toList();
    }

    @Override
    public String imageUrlOf(Long postId, Long fileId) {
        if (fileId == null) {
            return null;
        }
        return appPublicBaseUrl.urlOf(ContentImageLocation.publicPathOf(postId, fileId));
    }

    @Override
    public boolean belongsToGallery(Long postId, Long fileId) {
        return fileReferenceService
                .findOneOfTarget(FileTargetType.CONTENT_POST, postId, fileId)
                .isPresent();
    }

    @Override
    public String viewUrlOf(Long postId, Long fileId) {
        FileReferenceEntity reference =
                fileReferenceService
                        .findOneOfTarget(FileTargetType.CONTENT_POST, postId, fileId)
                        .orElseThrow(
                                () ->
                                        new GeneralException(
                                                ContentErrorCode.CONTENT_IMAGE_NOT_FOUND));
        return filePresigner.presignGet(reference.objectKey());
    }

    @Override
    public long viewRedirectCacheMaxAgeSeconds() {
        return filePresigner.viewRedirectCacheMaxAgeSeconds();
    }
}
