package org.sscc.ssccopsserver.domain.academicprogram.service;

import java.util.List;

import org.sscc.ssccopsserver.domain.academicprogram.dto.AttendancePatchRequest;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AttendancePatchResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AttendanceResponse;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

public interface AttendanceService {

    /* 출석부 — 그 활동의 관계자만 (#688) */
    List<AttendanceResponse> getAttendances(
            Long academicProgramId, Long sessionId, MemberEntity requester);

    AttendancePatchResponse correctAttendances(
            Long academicProgramId,
            Long sessionId,
            AttendancePatchRequest request,
            MemberEntity requester);
}
