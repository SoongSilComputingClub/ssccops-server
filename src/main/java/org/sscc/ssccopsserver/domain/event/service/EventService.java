package org.sscc.ssccopsserver.domain.event.service;

import java.util.List;

import org.sscc.ssccopsserver.domain.event.code.EventStatus;
import org.sscc.ssccopsserver.domain.event.dto.EventDetailResponse;
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
}
