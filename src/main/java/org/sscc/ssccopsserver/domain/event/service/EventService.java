package org.sscc.ssccopsserver.domain.event.service;

import java.util.List;

import org.sscc.ssccopsserver.domain.event.code.EventStatus;
import org.sscc.ssccopsserver.domain.event.dto.EventDetailResponse;
import org.sscc.ssccopsserver.domain.event.dto.EventDuplicateResponse;
import org.sscc.ssccopsserver.domain.event.dto.EventSaveRequest;
import org.sscc.ssccopsserver.domain.event.dto.EventStatusChangeRequest;
import org.sscc.ssccopsserver.domain.event.dto.EventSummaryResponse;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

public interface EventService {

    List<EventSummaryResponse> getEvents(String classificationCode, EventStatus statusCode);

    EventDetailResponse getEvent(Long eventId);

    EventDetailResponse createEvent(EventSaveRequest request, MemberEntity creator);

    EventDetailResponse updateEvent(Long eventId, EventSaveRequest request);

    EventDetailResponse changeStatus(Long eventId, EventStatusChangeRequest request);

    EventDuplicateResponse duplicateEvent(Long eventId, MemberEntity creator);

    /*
     * 공유 링크 발급 전 가드 (ssccops#312). 없는 행사는 404 EVENT_NOT_FOUND, 게시·보관된
     * 행사는 409 EVENT_SHARE_NOT_DRAFT다.
     *
     * **컨트롤러가 상태를 보고 분기하지 않도록 여기 둔다** — "이 행사를 공유할 수 있는가"는
     * 도메인의 판정이고(LY-02), 컨트롤러가 `getEvent()`의 응답에서 상태를 꺼내 비교하면 그
     * 규칙이 API 층에 눌러앉는다. 근거는 EVENT_SHARE_NOT_DRAFT 주석에 있다.
     */
    void requireShareableDraft(Long eventId);
}
