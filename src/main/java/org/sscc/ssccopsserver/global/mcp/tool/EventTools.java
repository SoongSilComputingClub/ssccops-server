package org.sscc.ssccopsserver.global.mcp.tool;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.event.code.EventStatus;
import org.sscc.ssccopsserver.domain.event.dto.EventDetailResponse;
import org.sscc.ssccopsserver.domain.event.dto.EventSaveRequest;
import org.sscc.ssccopsserver.domain.event.dto.EventStatusChangeRequest;
import org.sscc.ssccopsserver.domain.event.dto.EventSummaryResponse;
import org.sscc.ssccopsserver.global.mcp.client.McpRestClient;
import org.sscc.ssccopsserver.global.mcp.tool.patch.EventPatch;

import io.modelcontextprotocol.common.McpTransportContext;

import lombok.RequiredArgsConstructor;

/*
 * 행사 도구 (ssccops#365 W2 · #494 · ADR-0027). «다음 주 MT 행사 만들어줘»가 되게.
 *
 * 규약은 다른 도구와 같다 — REST만 부르고(`/v1/events` · 전부 EVENT_MANAGE), 타입은 컨트롤러 record
 * 그대로, 로그는 이름과 id만. **복제·삭제·복원·이미지·공유 도구는 없다** — 복제는 폼 연결·이미지를
 * 어떻게 승계하나가 미결(ssccops#194 B1)이고, 삭제는 W5(ADR-0037), 이미지는 바이트를 올릴 손이 없다.
 *
 * 수정은 PUT(전체 교체)이라 «읽고-합치기»다(EventPatch).
 */
@Component
@RequiredArgsConstructor
public class EventTools {

    private static final Logger log = LoggerFactory.getLogger(EventTools.class);

    private final McpRestClient client;

    /** list_events의 조건 — 둘 다 선택. 값은 어드민 목록 필터와 같다 */
    public record EventListCondition(String eventClsfCd, EventStatus eventSttsCd) {}

    @McpTool(
            name = "list_events",
            description =
                    "행사 목록(최신 먼저). eventClsfCd(분류 코드)·eventSttsCd(DRAFT·PUBLISHED·ARCHIVED)로"
                            + " 거른다, 둘 다 비우면 전체. 목록에는 본문(mtxtCn)이 없다 — get_event로."
                            + " 행사 관리(EVENT_MANAGE) 권한이 필요하다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public List<EventSummaryResponse> listEvents(
            @McpToolParam(description = "검색 조건 — eventClsfCd·eventSttsCd. 전부 선택", required = false)
                    EventListCondition condition,
            McpTransportContext context) {
        log.info("mcp tool list_events");
        return client.getList(context, "/v1/events", condition, EventSummaryResponse.class).items();
    }

    @McpTool(
            name = "get_event",
            description = "행사 하나 — 본문·기간·장소·정원·연결된 폼(formId)과 그 접수 상태·확정 인원. 없거나 지운" + " 행사는 404.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public EventDetailResponse getEvent(
            @McpToolParam(description = "행사 id") Long eventId, McpTransportContext context) {
        log.info("mcp tool get_event eventId={}", eventId);
        return client.get(context, "/v1/events/" + eventId, EventDetailResponse.class);
    }

    @McpTool(
            name = "create_event",
            description =
                    "행사를 초안으로 만든다. eventClsfCd(분류 코드)·eventTtl(제목)·mtxtCn(본문 마크다운)이"
                            + " 필수이고 기간(eventBgngDt·eventEndDt · ISO-8601 오프셋)·장소(plcNm)·"
                            + "정원(ptcpLmtCnt)·신청 폼(formId)은 선택이다. **만들어도 공개되지 않는다** —"
                            + " 공개는 change_event_status(PUBLISH). 분류 코드는 어드민 «행사 분류»의 값이다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public EventDetailResponse createEvent(
            @McpToolParam(description = "행사 등록 요청") EventSaveRequest request,
            McpTransportContext context) {
        log.info("mcp tool create_event");
        return client.post(context, "/v1/events", request, EventDetailResponse.class);
    }

    @McpTool(
            name = "update_event",
            description =
                    "행사의 값을 바꾼다. **바꿀 필드만 준다** — 나머지는 현재 값이 유지된다. 폼 연결·정원·"
                            + "장소를 **비우는** 것은 이 도구로 할 수 없다(어드민 화면에서). 서버 PUT이 전체"
                            + " 교체라 상세를 읽어 합친 뒤 보낸다 — 그 사이 다른 사람이 고쳤으면 그 값을 덮는다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public EventDetailResponse updateEvent(
            @McpToolParam(description = "행사 id") Long eventId,
            @McpToolParam(description = "바꿀 필드만. 비운 필드는 현재 값을 유지한다") EventPatch patch,
            McpTransportContext context) {
        log.info("mcp tool update_event eventId={}", eventId);
        EventDetailResponse current =
                client.get(context, "/v1/events/" + eventId, EventDetailResponse.class);
        return client.put(
                context, "/v1/events/" + eventId, patch.merge(current), EventDetailResponse.class);
    }

    @McpTool(
            name = "change_event_status",
            description =
                    "행사 상태를 바꾼다 — action에 PUBLISH(초안→공개)·RETRACT(공개→초안)·ARCHIVE(보관)·"
                            + "REPUBLISH(보관→공개) 중 하나. 허용되지 않는 전이는 409. **공개는 익명 사이트에"
                            + " 바로 나온다**(CDN 캐시로 최대 5분).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public EventDetailResponse changeEventStatus(
            @McpToolParam(description = "행사 id") Long eventId,
            @McpToolParam(description = "action — PUBLISH·RETRACT·ARCHIVE·REPUBLISH")
                    EventStatusChangeRequest request,
            McpTransportContext context) {
        log.info("mcp tool change_event_status eventId={}", eventId);
        return client.post(
                context, "/v1/events/" + eventId + "/status", request, EventDetailResponse.class);
    }
}
