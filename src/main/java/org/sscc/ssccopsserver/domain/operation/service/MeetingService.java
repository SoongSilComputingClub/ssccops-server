package org.sscc.ssccopsserver.domain.operation.service;

import java.util.List;

import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingAgendaItemRequest;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingAgendaResponse;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingAgendaUpdateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingCreateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingDetailResponse;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingListItemResponse;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingTransitionRequest;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingTransitionResponse;

public interface MeetingService {

    MeetingDetailResponse createMeeting(MeetingCreateRequest request, MemberEntity registrant);

    MeetingDetailResponse getMeeting(Long meetingId);

    List<MeetingListItemResponse> listMeetings();

    MeetingTransitionResponse transitionMeeting(
            Long meetingId, MeetingTransitionRequest request, MemberEntity performer);

    List<MeetingAgendaResponse> getAgendas(Long meetingId);

    MeetingAgendaResponse addAgenda(
            Long meetingId, MeetingAgendaItemRequest request, MemberEntity submitter);

    MeetingAgendaResponse updateAgenda(
            Long meetingId, Long agendaId, MeetingAgendaUpdateRequest request);

    void withdrawAgenda(Long meetingId, Long agendaId);
}
