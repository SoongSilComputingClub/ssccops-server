package org.sscc.ssccopsserver.global.mcp.tool.patch;

import java.time.LocalDate;

import org.sscc.ssccopsserver.domain.content.code.ContentCategory;
import org.sscc.ssccopsserver.domain.content.dto.ContentPostResponse;
import org.sscc.ssccopsserver.domain.content.dto.ContentPostSaveRequest;

/*
 * update_post의 «바꿀 것만» record (ssccops#381). ContentPostSaveRequest와 1:1이다. null이 «안 바꿈»
 * 이라 행사·표지를 **비우는** 것은 이 도구로 할 수 없다(WorkPatch가 총평을 비우지 못하는 것과 같은
 * 한계) — 그것은 어드민 화면에서 한다.
 *
 * **요약은 빈 문자열(공백뿐 포함)이 «비움»이다**(#662). 비우면 null로 보낸다 — 어드민 화면이
 * 비운 요약을 null로 저장하므로(`smry.trim() || null`) 같은 «요약 없음»이 DB에 ""와 null 두 모양으로
 * 남지 않게 한다. 그전에는 ""가 그대로 저장돼 설명(«비울 수 없다»)과 실제가 갈렸다. 공개 사이트는
 * 둘을 같게 다루지만(카드·상세·OG가 falsy로 본다) 저장 값까지 맞춘다.
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
                smry != null ? blankToNull(smry) : current.smry(),
                mtxt != null ? mtxt : current.mtxt(),
                actvYmd != null ? actvYmd : current.actvYmd(),
                eventId != null ? eventId : current.eventId(),
                coverFileId != null ? coverFileId : current.coverFileId());
    }

    private static String blankToNull(String value) {
        return value.isBlank() ? null : value;
    }
}
