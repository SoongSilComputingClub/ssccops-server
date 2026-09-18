package org.sscc.ssccopsserver.global.mcp.tool.patch;

import org.sscc.ssccopsserver.domain.content.dto.ContentPageResponse;
import org.sscc.ssccopsserver.domain.content.dto.ContentPageSaveRequest;

/*
 * update_page의 «바꿀 것만» record (ssccops#381). 서버 PATCH가 통째로 교체라(ContentPageController)
 * WorkPatch와 같은 읽고-합치기다 — ContentPageSaveRequest와 필드 이름·타입이 1:1이며
 * McpPatchContractTest가 그것을 반사로 본다.
 */
public record ContentPagePatch(String slug, String ttl, String mtxt) {

    public ContentPageSaveRequest merge(ContentPageResponse current) {
        return new ContentPageSaveRequest(
                slug != null ? slug : current.slug(),
                ttl != null ? ttl : current.ttl(),
                mtxt != null ? mtxt : current.mtxt());
    }
}
