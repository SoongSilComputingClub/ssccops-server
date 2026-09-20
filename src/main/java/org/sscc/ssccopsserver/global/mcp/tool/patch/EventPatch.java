package org.sscc.ssccopsserver.global.mcp.tool.patch;

import java.time.OffsetDateTime;

import org.sscc.ssccopsserver.domain.event.dto.EventDetailResponse;
import org.sscc.ssccopsserver.domain.event.dto.EventSaveRequest;

/*
 * update_event의 입력 — 바꿀 것만 (W2 · #494). `EventSaveRequest`와 같은 필드이고(McpPatchContractTest가
 * 대조) 전부 선택이다. 행사 수정은 PUT(전체 교체)이라 상세를 GET해 빈 자리를 채운다 — 다른 Patch와 같다.
 *
 * **비우는 것은 이 도구로 할 수 없다** — null은 «안 바꿈»이지 «지움»이 아니다. 폼 연결·정원·장소를
 * 비우려면 어드민 화면에서.
 */
public record EventPatch(
        String eventClsfCd,
        String eventTtl,
        String mtxtCn,
        String thmbUrlAddr,
        Long formId,
        OffsetDateTime eventBgngDt,
        OffsetDateTime eventEndDt,
        String plcNm,
        Integer ptcpLmtCnt) {

    public EventSaveRequest merge(EventDetailResponse current) {
        return new EventSaveRequest(
                eventClsfCd != null ? eventClsfCd : current.eventClsfCd(),
                eventTtl != null ? eventTtl : current.eventTtl(),
                mtxtCn != null ? mtxtCn : current.mtxtCn(),
                thmbUrlAddr != null ? thmbUrlAddr : current.thmbUrlAddr(),
                formId != null ? formId : current.formId(),
                eventBgngDt != null ? eventBgngDt : current.eventBgngDt(),
                eventEndDt != null ? eventEndDt : current.eventEndDt(),
                plcNm != null ? plcNm : current.plcNm(),
                ptcpLmtCnt != null ? ptcpLmtCnt : current.ptcpLmtCnt());
    }
}
