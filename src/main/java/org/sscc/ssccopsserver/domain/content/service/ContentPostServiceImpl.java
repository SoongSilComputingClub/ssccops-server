package org.sscc.ssccopsserver.domain.content.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.content.code.ContentBody;
import org.sscc.ssccopsserver.domain.content.code.ContentCategory;
import org.sscc.ssccopsserver.domain.content.code.ContentPublishStatus;
import org.sscc.ssccopsserver.domain.content.code.error.ContentErrorCode;
import org.sscc.ssccopsserver.domain.content.dto.ContentIdCursor;
import org.sscc.ssccopsserver.domain.content.dto.ContentPostHistoryResponse;
import org.sscc.ssccopsserver.domain.content.dto.ContentPostResponse;
import org.sscc.ssccopsserver.domain.content.dto.ContentPostSaveRequest;
import org.sscc.ssccopsserver.domain.content.dto.ContentPostSearchResponse;
import org.sscc.ssccopsserver.domain.content.dto.ContentPostSummaryResponse;
import org.sscc.ssccopsserver.domain.content.entity.ContentPostEntity;
import org.sscc.ssccopsserver.domain.content.entity.ContentPostHistoryEntity;
import org.sscc.ssccopsserver.domain.content.repository.ContentPostHistoryRepository;
import org.sscc.ssccopsserver.domain.content.repository.ContentPostRepository;
import org.sscc.ssccopsserver.domain.event.code.error.EventErrorCode;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.event.repository.EventRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.PageResponse;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;
import org.sscc.ssccopsserver.global.audit.AuditAction;
import org.sscc.ssccopsserver.global.audit.AuditEvent;
import org.sscc.ssccopsserver.global.audit.AuditLog;

import lombok.RequiredArgsConstructor;

/*
 * 포스트 관리 (ssccops#381 · ADR-0038). 이력·감사·본문 상한·slug 충돌의 규칙은
 * ContentPageServiceImpl과 같다 — 그쪽 클래스 주석이 정본이다. 여기에만 있는 것은 둘이다:
 *
 * **표지 검증.** coverFileId는 이 포스트의 갤러리(file_rfrnc · CONTENT_POST · trgt_id = post_id)에
 * 있는 파일이어야 한다(400 COVER_NOT_IN_GALLERY). 남의 포스트의 파일 id를 표지로 삼는 경로를
 * 막는 것이며, 엔티티가 파일 참조를 조회할 수 없어 서비스가 본다.
 *
 * **from-event.** 행사(event)의 제목·일시·장소·본문을 복사한 초안을 만든다 — 행사가 끝난 뒤
 * «갈무리 글»의 출발점이다. 행사 도메인을 한 방향으로만 읽는다(EventRepository — content →
 * event · 행사는 콘텐츠를 모른다 · DomainCycleTest). 복사이지 연결이 아니다: 그 뒤 행사가
 * 바뀌어도 포스트는 따라가지 않고, eventId는 www가 링크를 붙이는 재료로만 남는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ContentPostServiceImpl implements ContentPostService {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter EVENT_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int SLUG_SUFFIX_LIMIT = 100;

    private final ContentPostRepository postRepository;
    private final ContentPostHistoryRepository historyRepository;
    private final ContentPostImageService imageService;
    private final EventRepository eventRepository;
    private final AuditLog auditLog;
    private final Clock clock;

    @Override
    public ContentPostSearchResponse getPosts(
            ContentPublishStatus status, ContentCategory category, int size, String cursor) {
        ContentIdCursor decoded = ContentIdCursor.decode(cursor);
        Long cursorId = decoded == null ? null : decoded.id();

        List<ContentPostEntity> fetched =
                postRepository.findAllForAdminList(
                        status, category, cursorId, PageRequest.of(0, size + 1));
        boolean hasNext = fetched.size() > size;
        List<ContentPostEntity> rows = hasNext ? fetched.subList(0, size) : fetched;

        long total = postRepository.countForAdminList(status, category);
        PageResponse page =
                new PageResponse(
                        size,
                        "id,desc",
                        hasNext
                                ? new ContentIdCursor(rows.get(rows.size() - 1).getId()).encode()
                                : null,
                        hasNext,
                        total,
                        status == null && category == null
                                ? total
                                : postRepository.countForAdminList(null, null));
        return new ContentPostSearchResponse(
                rows.stream().map(ContentPostSummaryResponse::of).toList(), page);
    }

    @Override
    public ContentPostResponse getPost(Long postId) {
        return toResponse(findPost(postId));
    }

    @Override
    @Transactional
    public ContentPostResponse createPost(ContentPostSaveRequest request, MemberEntity modifier) {
        requireBodyWithinLimit(request.mtxt());
        if (postRepository.existsBySlug(request.slug())) {
            throw new GeneralException(ContentErrorCode.CONTENT_SLUG_DUPLICATED);
        }
        // 표지는 갤러리가 있어야 고를 수 있고 갤러리는 포스트 id가 있어야 생긴다 — 생성 본문의
        // coverFileId는 어떤 값이든 갤러리에 없으므로 값이 오면 그대로 400이다
        if (request.coverFileId() != null) {
            throw new GeneralException(ContentErrorCode.COVER_NOT_IN_GALLERY);
        }
        ContentPostEntity post =
                ContentPostEntity.create(
                        request.slug(),
                        request.cntntClsfCd(),
                        request.ttl(),
                        request.smry(),
                        request.mtxt(),
                        request.actvYmd(),
                        request.eventId(),
                        modifier);
        saveOrConflict(post);
        record(post, modifier);
        return toResponse(post);
    }

    /*
     * 행사 → 포스트 초안. 복사하는 것은 제목·활동일(행사 시작일, 없으면 오늘)·장소·본문이다.
     * 장소·일시는 본문 맨 앞에 인용 블록 한 줄로 넣는다 — 포스트에는 그 컬럼이 없고(갈무리
     * 글에 정원·폼은 무의미하다) 본문에 남겨 두면 편집하며 지우거나 살릴 수 있다.
     * slug는 event-{id}이고 이미 있으면 -2, -3…을 붙인다(같은 행사를 두 번 갈무리하는 것도
     * 정상이다 — 사진 편·후기 편). 분류는 EVENT, 상태는 DRAFT, 요약은 비운다.
     * 지운 행사·없는 행사는 행사 도메인과 같은 404 EVENT_NOT_FOUND다.
     */
    @Override
    @Transactional
    public ContentPostResponse createPostFromEvent(Long eventId, MemberEntity modifier) {
        EventEntity event =
                eventRepository
                        .findByIdAndDeletedAtIsNull(eventId)
                        .orElseThrow(() -> new GeneralException(EventErrorCode.EVENT_NOT_FOUND));

        LocalDate activityDate =
                event.getBeginAt() == null
                        ? LocalDate.ofInstant(clock.instant(), SERVICE_ZONE)
                        : LocalDate.ofInstant(event.getBeginAt(), SERVICE_ZONE);
        String body = bodyFromEvent(event);
        requireBodyWithinLimit(body);

        ContentPostEntity post =
                ContentPostEntity.create(
                        availableSlugFor("event-" + eventId),
                        ContentCategory.EVENT,
                        event.getTitle(),
                        null,
                        body,
                        activityDate,
                        eventId,
                        modifier);
        saveOrConflict(post);
        record(post, modifier);
        return toResponse(post);
    }

    @Override
    @Transactional
    public ContentPostResponse updatePost(
            Long postId, ContentPostSaveRequest request, MemberEntity modifier) {
        requireBodyWithinLimit(request.mtxt());
        ContentPostEntity post = findPost(postId);
        if (postRepository.existsBySlugAndIdNot(request.slug(), postId)) {
            throw new GeneralException(ContentErrorCode.CONTENT_SLUG_DUPLICATED);
        }
        if (request.coverFileId() != null
                && !imageService.belongsToGallery(postId, request.coverFileId())) {
            throw new GeneralException(ContentErrorCode.COVER_NOT_IN_GALLERY);
        }
        post.update(
                request.slug(),
                request.cntntClsfCd(),
                request.ttl(),
                request.smry(),
                request.mtxt(),
                request.actvYmd(),
                request.eventId(),
                request.coverFileId(),
                modifier);
        saveOrConflict(post);
        record(post, modifier);
        return toResponse(post);
    }

    @Override
    @Transactional
    public ContentPostResponse publishPost(Long postId, MemberEntity modifier) {
        ContentPostEntity post = findPost(postId);
        post.publish(clock.instant(), modifier);
        postRepository.flush();
        record(post, modifier);
        auditLog.record(
                AuditEvent.success(AuditAction.CONTENT_POST_PUBLISH)
                        .target(postId)
                        .after(post.getPublishStatus())
                        .build());
        return toResponse(post);
    }

    @Override
    @Transactional
    public ContentPostResponse unpublishPost(Long postId, MemberEntity modifier) {
        ContentPostEntity post = findPost(postId);
        post.unpublish(modifier);
        postRepository.flush();
        record(post, modifier);
        auditLog.record(
                AuditEvent.success(AuditAction.CONTENT_POST_UNPUBLISH)
                        .target(postId)
                        .after(post.getPublishStatus())
                        .build());
        return toResponse(post);
    }

    @Override
    public List<ContentPostHistoryResponse> getPostHistory(Long postId) {
        findPost(postId);
        return historyRepository.findAllByPostId(postId).stream()
                .map(ContentPostHistoryResponse::of)
                .toList();
    }

    private ContentPostEntity findPost(Long postId) {
        return postRepository
                .findById(postId)
                .orElseThrow(() -> new GeneralException(ContentErrorCode.POST_NOT_FOUND));
    }

    private ContentPostResponse toResponse(ContentPostEntity post) {
        return ContentPostResponse.of(post, imageService.galleryOf(post.getId()));
    }

    private void record(ContentPostEntity post, MemberEntity changer) {
        historyRepository.save(ContentPostHistoryEntity.snapshotOf(post, changer, clock.instant()));
    }

    private static void requireBodyWithinLimit(String body) {
        if (ContentBody.isTooLarge(body)) {
            throw new GeneralException(ContentErrorCode.CONTENT_TOO_LARGE);
        }
    }

    private void saveOrConflict(ContentPostEntity post) {
        try {
            postRepository.saveAndFlush(post);
        } catch (DataIntegrityViolationException ex) {
            throw new GeneralException(ContentErrorCode.CONTENT_SLUG_DUPLICATED);
        }
    }

    private String availableSlugFor(String base) {
        if (!postRepository.existsBySlug(base)) {
            return base;
        }
        for (int suffix = 2; suffix <= SLUG_SUFFIX_LIMIT; suffix++) {
            String candidate = base + "-" + suffix;
            if (!postRepository.existsBySlug(candidate)) {
                return candidate;
            }
        }
        // 같은 행사를 백 번 갈무리한 상태 — 있을 수 없는 값이라 무작위 꼬리로 끝낸다
        return base + "-" + Long.toString(System.nanoTime(), 36);
    }

    private static String bodyFromEvent(EventEntity event) {
        StringBuilder header = new StringBuilder();
        if (event.getBeginAt() != null) {
            header.append("일시: ").append(formatRange(event.getBeginAt(), event.getEndAt()));
        }
        if (event.getPlaceName() != null && !event.getPlaceName().isBlank()) {
            if (header.length() > 0) {
                header.append(" · ");
            }
            header.append("장소: ").append(event.getPlaceName());
        }
        String markdown = event.getContentMarkdown() == null ? "" : event.getContentMarkdown();
        if (header.length() == 0) {
            return markdown;
        }
        return "> " + header + "\n\n" + markdown;
    }

    private static String formatRange(Instant beginAt, Instant endAt) {
        String begin = EVENT_DATE_FORMAT.format(beginAt.atZone(SERVICE_ZONE));
        if (endAt == null) {
            return begin;
        }
        return begin + " ~ " + EVENT_DATE_FORMAT.format(endAt.atZone(SERVICE_ZONE));
    }
}
