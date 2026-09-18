package org.sscc.ssccopsserver.domain.content.service;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.content.code.ContentCategory;
import org.sscc.ssccopsserver.domain.content.code.ContentPublishStatus;
import org.sscc.ssccopsserver.domain.content.code.error.ContentErrorCode;
import org.sscc.ssccopsserver.domain.content.dto.ContentPostCursor;
import org.sscc.ssccopsserver.domain.content.dto.PublicContentPageResponse;
import org.sscc.ssccopsserver.domain.content.dto.PublicContentPostDetailResponse;
import org.sscc.ssccopsserver.domain.content.dto.PublicContentPostSearchResponse;
import org.sscc.ssccopsserver.domain.content.dto.PublicContentPostSummaryResponse;
import org.sscc.ssccopsserver.domain.content.entity.ContentPostEntity;
import org.sscc.ssccopsserver.domain.content.repository.ContentPageRepository;
import org.sscc.ssccopsserver.domain.content.repository.ContentPostRepository;
import org.sscc.ssccopsserver.global.apipayload.PageResponse;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;

/*
 * 익명 조회 (ssccops#381 · ADR-0038). 게시 상태는 **질의 조건**이다 — 조회 뒤 거르지 않는다
 * (PublicEventServiceImpl과 같은 태도). 그래서 초안·없는 것이 같은 404이고 «초안이 있다»는
 * 사실이 응답 모양에서 새지 않는다.
 *
 * 목록은 활동일 역순(같은 날은 id 역순)이며 커서는 그 두 값이다(ContentPostCursor). totalCount는
 * 분류 필터를 적용한 게시본 수, overallCount는 게시본 전체 수 — www 탭이 «학술 12 · 전체 40»을
 * 그리는 재료다.
 *
 * 감사 로그를 남기지 않는다 — 익명 조회는 주체가 없고 목록 조회는 원래 남기지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PublicContentServiceImpl implements PublicContentService {

    private static final String SORT = "actvYmd,desc";

    private final ContentPageRepository pageRepository;
    private final ContentPostRepository postRepository;
    private final ContentPostImageService imageService;

    @Override
    public PublicContentPageResponse getPublishedPage(String slug) {
        return pageRepository
                .findBySlugAndPublishStatus(slug, ContentPublishStatus.PUBLISHED)
                .map(PublicContentPageResponse::of)
                .orElseThrow(() -> new GeneralException(ContentErrorCode.PAGE_NOT_FOUND));
    }

    @Override
    public PublicContentPostSearchResponse getPublishedPosts(
            ContentCategory category, int size, String cursor) {
        ContentPostCursor decoded = ContentPostCursor.decode(cursor);

        List<ContentPostEntity> fetched =
                postRepository.findAllForPublicList(
                        ContentPublishStatus.PUBLISHED,
                        category,
                        decoded == null ? null : decoded.activityDate(),
                        decoded == null ? null : decoded.postId(),
                        PageRequest.of(0, size + 1));
        boolean hasNext = fetched.size() > size;
        List<ContentPostEntity> rows = hasNext ? fetched.subList(0, size) : fetched;

        long total = postRepository.countForPublicList(ContentPublishStatus.PUBLISHED, category);
        PageResponse page =
                new PageResponse(
                        size,
                        SORT,
                        hasNext ? ContentPostCursor.of(rows.get(rows.size() - 1)).encode() : null,
                        hasNext,
                        total,
                        category == null
                                ? total
                                : postRepository.countForPublicList(
                                        ContentPublishStatus.PUBLISHED, null));

        return new PublicContentPostSearchResponse(
                rows.stream()
                        .map(
                                post ->
                                        PublicContentPostSummaryResponse.of(
                                                post,
                                                imageService.imageUrlOf(
                                                        post.getId(), post.getCoverFileId())))
                        .toList(),
                page);
    }

    @Override
    public PublicContentPostDetailResponse getPublishedPost(String slug) {
        ContentPostEntity post =
                postRepository
                        .findBySlugAndPublishStatus(slug, ContentPublishStatus.PUBLISHED)
                        .orElseThrow(() -> new GeneralException(ContentErrorCode.POST_NOT_FOUND));
        return PublicContentPostDetailResponse.of(
                post,
                imageService.imageUrlOf(post.getId(), post.getCoverFileId()),
                imageService.galleryOf(post.getId()));
    }

    /*
     * 게시본이 아니면 상세와 같은 404 POST_NOT_FOUND — 초안의 갤러리가 주소만으로 열리면
     * «게시 전에는 익명이 아무것도 못 본다»가 이미지에서 깨진다. 없는 파일은 그 뒤 404
     * CONTENT_IMAGE_NOT_FOUND다(순서가 바뀌면 초안의 파일 유무가 코드로 샌다).
     */
    @Override
    public String viewUrlOf(Long postId, Long fileId) {
        if (!postRepository
                .findByIdAndPublishStatus(postId, ContentPublishStatus.PUBLISHED)
                .isPresent()) {
            throw new GeneralException(ContentErrorCode.POST_NOT_FOUND);
        }
        return imageService.viewUrlOf(postId, fileId);
    }

    @Override
    public long viewRedirectCacheMaxAgeSeconds() {
        return imageService.viewRedirectCacheMaxAgeSeconds();
    }
}
