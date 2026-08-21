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

    /*
     * 회의를 소프트 삭제한다 (#125). 자기 operation만 del_dt를 채운다 — 안건(mtg_dtl)은
     * 지우지 않고 그대로 둔다. 상태와 무관하게 항상 허용한다(완료·취소된 회의도 삭제 가능).
     * MEETING_DELETE 보유 여부만으로 게이트가 걸린다 — 회의 책임자 본인 여부는 보지 않는다.
     *
     * 대상이 아예 없으면 MEETING_NOT_FOUND(404), 있지만 이미 삭제됐으면 ALREADY_DELETED(409)다.
     */
    void deleteMeeting(Long meetingId);
}
