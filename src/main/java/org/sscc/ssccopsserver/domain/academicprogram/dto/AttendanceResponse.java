package org.sscc.ssccopsserver.domain.academicprogram.dto;

import org.sscc.ssccopsserver.domain.academicprogram.entity.AttendanceEntity;
import org.sscc.ssccopsserver.domain.event.entity.EventParticipantEntity;

/*
 * 출석부 한 줄 (#137 · 학술관리_API설계.md §3.5 — GET·PATCH .../attendances).
 *
 * 회차 상세(#135)의 SessionAttendanceResponse와 값이 겹치지만 attendanceId가 하나 더 있고,
 * 두 응답을 합치지 않는다. 상세는 "계획 대비 실제"를 보여주는 화면이라 출석부가 그 안의 한
 * 블록이고 줄을 개별 지목할 일이 없지만, 출석 화면은 출석부 자체가 목적이라 줄마다 식별자가
 * 필요하다(정정 결과를 화면이 줄 단위로 되짚는다). 이미 웹과 합의된 #135 응답에 필드를 더해
 * 두 화면이 한 DTO를 공유하게 만들면, 다음에 한쪽만 필요한 값이 생길 때마다 다른 쪽 계약이
 * 함께 넓어진다(PublicFormResponse를 FormResponse와 나눈 것과 같은 판단).
 *
 * 회원명은 attendance에 복사하지 않고 event_ptcp → mbr로 조인해 싣는다 — 복사해 두면 개명한
 * 회원의 이름이 회차마다 다르게 남는다.
 */
public record AttendanceResponse(
        Long attendanceId, Long eventPtcpId, String mbrNm, boolean presentYn) {

    public static AttendanceResponse from(AttendanceEntity attendance) {
        EventParticipantEntity participant = attendance.getParticipant();
        return new AttendanceResponse(
                attendance.getId(),
                participant.getId(),
                participant.getMember().getName(),
                attendance.isPresent());
    }
}
