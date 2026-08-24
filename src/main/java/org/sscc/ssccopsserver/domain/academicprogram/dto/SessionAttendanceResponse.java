package org.sscc.ssccopsserver.domain.academicprogram.dto;

import org.sscc.ssccopsserver.domain.academicprogram.entity.AttendanceEntity;
import org.sscc.ssccopsserver.domain.event.entity.EventParticipantEntity;

/*
 * 회차 상세(#135)의 출석부 한 줄. 회원명은 attendance에 복사하지 않고 event_ptcp → mbr로
 * 조인해 싣는다(참가자 명단 응답과 같은 태도) — 복사해 두면 개명한 회원의 이름이 회차마다
 * 다르게 남는다.
 */
public record SessionAttendanceResponse(Long eventPtcpId, String mbrNm, boolean presentYn) {

    public static SessionAttendanceResponse from(AttendanceEntity attendance) {
        EventParticipantEntity participant = attendance.getParticipant();
        return new SessionAttendanceResponse(
                participant.getId(), participant.getMember().getName(), attendance.isPresent());
    }
}
