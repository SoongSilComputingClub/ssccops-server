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
     * 소프트 삭제 (#347 · ADR-0020 · DELETE /v1/events/{eventId}). 없는 행사는 404, 이미 지운
     * 행사는 409 ALREADY_DELETED, 학술 활동이 딸린 행사는 409 EVENT_HAS_ACADEMIC_PROGRAM이다.
     * 참가자 수는 보지 않는다.
     */
    void deleteEvent(Long eventId);

    /*
     * 되살리기 (#347 · POST /v1/events/{eventId}/restore). 지우기 직전 상태 그대로 돌아온다.
     * 지워지지 않은 행사는 409 NOT_DELETED, 지워진 동안 그 폼을 다른 행사가 가져갔으면
     * 409 FORM_ALREADY_LINKED다.
     */
    void restoreEvent(Long eventId);

    /** 휴지통 목록 (#347 · GET /v1/events/deleted). 지운 시각 역순이다 */
    List<EventSummaryResponse> getDeletedEvents();

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
