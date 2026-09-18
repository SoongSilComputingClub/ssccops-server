package org.sscc.ssccopsserver.global.mcp.tool.patch;

import java.time.LocalDate;

import org.sscc.ssccopsserver.domain.content.code.ContentCategory;
import org.sscc.ssccopsserver.domain.content.dto.ContentPostResponse;
import org.sscc.ssccopsserver.domain.content.dto.ContentPostSaveRequest;

/*
 * update_post의 «바꿀 것만» record (ssccops#381). ContentPostSaveRequest와 1:1이다. null이 «안 바꿈»
 * 이라 요약·행사·표지를 **비우는** 것은 이 도구로 할 수 없다(WorkPatch가 총평을 비우지 못하는
 * 것과 같은 한계) — 그것은 어드민 화면에서 한다.
 */
public record ContentPostPatch(
        String slug,
        ContentCategory cntntClsfCd,
        String ttl,
        String smry,
        String mtxt,
        LocalDate actvYmd,
        Long eventId,
        Long coverFileId) {

    public ContentPostSaveRequest merge(ContentPostResponse current) {
        return new ContentPostSaveRequest(
                slug != null ? slug : current.slug(),
                cntntClsfCd != null ? cntntClsfCd : current.cntntClsfCd(),
                ttl != null ? ttl : current.ttl(),
                smry != null ? smry : current.smry(),
                mtxt != null ? mtxt : current.mtxt(),
                actvYmd != null ? actvYmd : current.actvYmd(),
                eventId != null ? eventId : current.eventId(),
                coverFileId != null ? coverFileId : current.coverFileId());
    }
}
