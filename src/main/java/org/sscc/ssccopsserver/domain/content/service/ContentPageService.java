package org.sscc.ssccopsserver.domain.content.service;

import java.util.List;

import org.sscc.ssccopsserver.domain.content.code.ContentPublishStatus;
import org.sscc.ssccopsserver.domain.content.dto.ContentPageHistoryResponse;
import org.sscc.ssccopsserver.domain.content.dto.ContentPageResponse;
import org.sscc.ssccopsserver.domain.content.dto.ContentPageSaveRequest;
import org.sscc.ssccopsserver.domain.content.dto.ContentPageSearchResponse;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/** 페이지 관리 (ssccops#381). 전부 CONTENT_MANAGE 뒤에 있다 — 인가는 컨트롤러가 본다 */
public interface ContentPageService {

    ContentPageSearchResponse getPages(ContentPublishStatus status, int size, String cursor);

    ContentPageResponse getPage(Long pageId);

    ContentPageResponse createPage(ContentPageSaveRequest request, MemberEntity modifier);

    ContentPageResponse updatePage(
            Long pageId, ContentPageSaveRequest request, MemberEntity modifier);

    ContentPageResponse publishPage(Long pageId, MemberEntity modifier);

    ContentPageResponse unpublishPage(Long pageId, MemberEntity modifier);

    List<ContentPageHistoryResponse> getPageHistory(Long pageId);
}
