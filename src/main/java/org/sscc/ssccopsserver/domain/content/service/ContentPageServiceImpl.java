package org.sscc.ssccopsserver.domain.content.service;

import java.time.Clock;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.content.code.ContentBody;
import org.sscc.ssccopsserver.domain.content.code.ContentPublishStatus;
import org.sscc.ssccopsserver.domain.content.code.error.ContentErrorCode;
import org.sscc.ssccopsserver.domain.content.dto.ContentIdCursor;
import org.sscc.ssccopsserver.domain.content.dto.ContentPageHistoryResponse;
import org.sscc.ssccopsserver.domain.content.dto.ContentPageResponse;
import org.sscc.ssccopsserver.domain.content.dto.ContentPageSaveRequest;
import org.sscc.ssccopsserver.domain.content.dto.ContentPageSearchResponse;
import org.sscc.ssccopsserver.domain.content.dto.ContentPageSummaryResponse;
import org.sscc.ssccopsserver.domain.content.entity.ContentPageEntity;
import org.sscc.ssccopsserver.domain.content.entity.ContentPageHistoryEntity;
import org.sscc.ssccopsserver.domain.content.repository.ContentPageHistoryRepository;
import org.sscc.ssccopsserver.domain.content.repository.ContentPageRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.PageResponse;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;
import org.sscc.ssccopsserver.global.audit.AuditAction;
import org.sscc.ssccopsserver.global.audit.AuditEvent;
import org.sscc.ssccopsserver.global.audit.AuditLog;

import lombok.RequiredArgsConstructor;

/*
 * 페이지 관리 (ssccops#381 · ADR-0038).
 *
 * **바꾸는 조작마다 이력이 한 행 는다** — 생성·수정·게시·게시 취소 넷 다. 변경을 적용한 뒤
 * 스냅샷을 찍으므로 이력의 마지막 행은 언제나 현재 모습과 같다(«지금 게시된 것이 어느 개정인가»
 * 를 이력만 보고 답할 수 있다). 스냅샷을 서비스가 찍고 엔티티가 찍지 않는 것은 이력 행이
 * 변경자·시각(요청 주체·Clock)을 요구하는데 엔티티는 그 둘을 모르기 때문이다.
 *
 * **게시·게시 취소만 감사 로그에 남긴다**(AuditAction.CONTENT_PAGE_PUBLISH/UNPUBLISH). 익명에게
 * 열리고 닫히는 사건이 그것이고, 작성·수정은 이력 테이블이 본문째 든다. 감사에는 본문 값이
 * 실리지 않는다(ADR-0024) — 대상 id와 결과 상태뿐이다.
 *
 * 본문 상한은 @Size가 아니라 여기서 413이다(ContentErrorCode.CONTENT_TOO_LARGE).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ContentPageServiceImpl implements ContentPageService {

    private final ContentPageRepository pageRepository;
    private final ContentPageHistoryRepository historyRepository;
    private final AuditLog auditLog;
    private final Clock clock;

    @Override
    public ContentPageSearchResponse getPages(
            ContentPublishStatus status, int size, String cursor) {
        ContentIdCursor decoded = ContentIdCursor.decode(cursor);
        Long cursorId = decoded == null ? null : decoded.id();

        // 다음 페이지가 있는지 알기 위해 한 건 더 읽는다
        List<ContentPageEntity> fetched =
                pageRepository.findAllForAdminList(status, cursorId, PageRequest.of(0, size + 1));
        boolean hasNext = fetched.size() > size;
        List<ContentPageEntity> rows = hasNext ? fetched.subList(0, size) : fetched;

        long total = pageRepository.countForAdminList(status);
        PageResponse page =
                new PageResponse(
                        size,
                        "id,desc",
                        hasNext
                                ? new ContentIdCursor(rows.get(rows.size() - 1).getId()).encode()
                                : null,
                        hasNext,
                        total,
                        status == null ? total : pageRepository.countForAdminList(null));
        return new ContentPageSearchResponse(
                rows.stream().map(ContentPageSummaryResponse::of).toList(), page);
    }

    @Override
    public ContentPageResponse getPage(Long pageId) {
        return ContentPageResponse.of(findPage(pageId));
    }

    @Override
    @Transactional
    public ContentPageResponse createPage(ContentPageSaveRequest request, MemberEntity modifier) {
        requireBodyWithinLimit(request.mtxt());
        if (pageRepository.existsBySlug(request.slug())) {
            throw new GeneralException(ContentErrorCode.CONTENT_SLUG_DUPLICATED);
        }
        ContentPageEntity page =
                ContentPageEntity.create(request.slug(), request.ttl(), request.mtxt(), modifier);
        saveOrConflict(page);
        record(page, modifier);
        return ContentPageResponse.of(page);
    }

    @Override
    @Transactional
    public ContentPageResponse updatePage(
            Long pageId, ContentPageSaveRequest request, MemberEntity modifier) {
        requireBodyWithinLimit(request.mtxt());
        ContentPageEntity page = findPage(pageId);
        if (pageRepository.existsBySlugAndIdNot(request.slug(), pageId)) {
            throw new GeneralException(ContentErrorCode.CONTENT_SLUG_DUPLICATED);
        }
        page.update(request.slug(), request.ttl(), request.mtxt(), modifier);
        saveOrConflict(page);
        record(page, modifier);
        return ContentPageResponse.of(page);
    }

    @Override
    @Transactional
    public ContentPageResponse publishPage(Long pageId, MemberEntity modifier) {
        ContentPageEntity page = findPage(pageId);
        page.publish(clock.instant(), modifier);
        pageRepository.flush();
        record(page, modifier);
        auditLog.record(
                AuditEvent.success(AuditAction.CONTENT_PAGE_PUBLISH)
                        .target(pageId)
                        .after(page.getPublishStatus())
                        .build());
        return ContentPageResponse.of(page);
    }

    @Override
    @Transactional
    public ContentPageResponse unpublishPage(Long pageId, MemberEntity modifier) {
        ContentPageEntity page = findPage(pageId);
        page.unpublish(modifier);
        pageRepository.flush();
        record(page, modifier);
        auditLog.record(
                AuditEvent.success(AuditAction.CONTENT_PAGE_UNPUBLISH)
                        .target(pageId)
                        .after(page.getPublishStatus())
                        .build());
        return ContentPageResponse.of(page);
    }

    @Override
    public List<ContentPageHistoryResponse> getPageHistory(Long pageId) {
        findPage(pageId);
        return historyRepository.findAllByPageId(pageId).stream()
                .map(ContentPageHistoryResponse::of)
                .toList();
    }

    private ContentPageEntity findPage(Long pageId) {
        return pageRepository
                .findById(pageId)
                .orElseThrow(() -> new GeneralException(ContentErrorCode.PAGE_NOT_FOUND));
    }

    private void record(ContentPageEntity page, MemberEntity changer) {
        historyRepository.save(ContentPageHistoryEntity.snapshotOf(page, changer, clock.instant()));
    }

    private static void requireBodyWithinLimit(String body) {
        if (ContentBody.isTooLarge(body)) {
            throw new GeneralException(ContentErrorCode.CONTENT_TOO_LARGE);
        }
    }

    /*
     * 선조회를 지나친 동시 생성·수정은 uk_cntnt_page_slug 위반으로만 드러난다 — 같은 409로 옮긴다
     * (FORM_ALREADY_LINKED · #21 학번 중복과 같은 방식). flush를 여기서 하는 것은 위반이 커밋
     * 시점이 아니라 이 자리에서 나게 하려는 것이다.
     */
    private void saveOrConflict(ContentPageEntity page) {
        try {
            pageRepository.saveAndFlush(page);
        } catch (DataIntegrityViolationException ex) {
            throw new GeneralException(ContentErrorCode.CONTENT_SLUG_DUPLICATED);
        }
    }
}
