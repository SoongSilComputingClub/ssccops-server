package org.sscc.ssccopsserver.domain.content.service;

import java.util.Comparator;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.content.code.ContentCategory;
import org.sscc.ssccopsserver.domain.content.code.ContentPublishStatus;
import org.sscc.ssccopsserver.domain.content.code.error.ContentErrorCode;
import org.sscc.ssccopsserver.domain.content.dto.ContentPostCursor;
import org.sscc.ssccopsserver.domain.content.dto.PublicContentPageResponse;
import org.sscc.ssccopsserver.domain.content.dto.PublicContentPageSummaryResponse;
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

    /*
     * 접두사 목록 (#513 · ssccops#425). www 역대 운영진이 «어떤 대수가 게시돼 있나»를 묻는 자리다 —
     * 그전에는 www가 손으로 적은 상수(`[44]`)를 들고 있어 43대 페이지가 게시돼 있어도 갈 길이 없었다.
     * ADR-0038의 기준(게시 상태인 것의 공개용 필드) 안이고 초안은 목록에 나타나지 않는다. 정렬은
     * slug 오름차순 — 숫자 대수의 내림차순은 www가 파싱해서 한다(슬러그는 문자열이라 `operators-9`가
     * `operators-44` 뒤에 온다).
     */
    @Override
    public List<PublicContentPageSummaryResponse> getPublishedPagesBySlugPrefix(String slugPrefix) {
        return pageRepository
                .findBySlugStartingWithAndPublishStatus(slugPrefix, ContentPublishStatus.PUBLISHED)
                .stream()
                .map(PublicContentPageSummaryResponse::of)
                .sorted(Comparator.comparing(PublicContentPageSummaryResponse::slug))
                .toList();
    }

    @Override
    public PublicContentPostSearchResponse getPublishedPosts(
            ContentCategory category, int size, String cursor) {
        ContentPostCursor decoded = ContentPostCursor.decode(cursor);

        // 커서 유무로 질의를 가른다 — 날짜 파라미터를 `is null`로 검사하면 PostgreSQL이 거절한다(#481)
        PageRequest limit = PageRequest.of(0, size + 1);
        List<ContentPostEntity> fetched =
                decoded == null
                        ? postRepository.findFirstPageForPublicList(
                                ContentPublishStatus.PUBLISHED, category, limit)
                        : postRepository.findAfterCursorForPublicList(
                                ContentPublishStatus.PUBLISHED,
                                category,
                                decoded.activityDate(),
                                decoded.postId(),
                                limit);
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
