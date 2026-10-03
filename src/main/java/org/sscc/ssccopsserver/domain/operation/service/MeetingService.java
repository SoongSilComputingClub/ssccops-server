package org.sscc.ssccopsserver.domain.operation.service;

import java.util.List;

import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingAgendaItemRequest;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingAgendaPromoteResponse;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingAgendaResponse;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingAgendaUpdateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingCreateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingDetailResponse;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingListItemResponse;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingTransitionRequest;
import org.sscc.ssccopsserver.domain.operation.dto.MeetingTransitionResponse;
import org.sscc.ssccopsserver.domain.operation.dto.WorkCreateRequest;

public interface MeetingService {

    MeetingDetailResponse createMeeting(MeetingCreateRequest request, MemberEntity registrant);

    MeetingDetailResponse getMeeting(Long meetingId);

    /*
     * 회의 전량 목록. tagId가 있으면 그 태그가 달린 회의만이다(#637 · null이면 전체 · 없는 태그 id는
     * 빈 결과).
     */
    List<MeetingListItemResponse> listMeetings(Long tagId);

    MeetingTransitionResponse transitionMeeting(
            Long meetingId, MeetingTransitionRequest request, MemberEntity performer);

    List<MeetingAgendaResponse> getAgendas(Long meetingId);

    MeetingAgendaResponse addAgenda(
            Long meetingId, MeetingAgendaItemRequest request, MemberEntity submitter);

    MeetingAgendaResponse updateAgenda(
            Long meetingId, Long agendaId, MeetingAgendaUpdateRequest request);

    void withdrawAgenda(Long meetingId, Long agendaId);

    /*
     * 드래프트 안건을 업무로 승격한다 (#625 · ADR-0059). 요청으로 받은 값으로 업무를 등록하고 그
     * 업무의 운영 건을 안건에 이은 뒤 안건 제목(agnd_nm)을 비운다 — 한 트랜잭션이다. registrant는
     * 업무 등록자이며 인증 주체에서 온다(LY-05).
     *
     * 회의·안건이 없으면 404, 안건이 이미 운영 건을 가리키면 MEETING_AGENDA_ALREADY_LINKED(409).
     * 종료·취소된 회의에서도 승격한다(#634) — 안건 추가·수정과 달리 MEETING_CLOSED를 내지 않는다. 업무 등록의 거절(담당자 400 등)은 그대로 난다.
     */
    MeetingAgendaPromoteResponse promoteAgendaToWork(
            Long meetingId, Long agendaId, WorkCreateRequest request, MemberEntity registrant);

    /*
     * 회의를 소프트 삭제한다 (#125). 자기 operation만 del_dt를 채운다 — 안건(mtg_dtl)은
     * 지우지 않고 그대로 둔다. 상태와 무관하게 항상 허용한다(완료·취소된 회의도 삭제 가능).
     * MEETING_DELETE 보유 여부만으로 게이트가 걸린다 — 회의 책임자 본인 여부는 보지 않는다.
     *
     * 대상이 아예 없으면 MEETING_NOT_FOUND(404), 있지만 이미 삭제됐으면 ALREADY_DELETED(409)다.
     */
    void deleteMeeting(Long meetingId);
}
