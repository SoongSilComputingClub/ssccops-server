package org.sscc.ssccopsserver.domain.academicprogram.service;

import java.util.List;

import org.sscc.ssccopsserver.domain.academicprogram.dto.AttendancePatchRequest;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AttendancePatchResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AttendanceResponse;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

public interface AttendanceService {

    List<AttendanceResponse> getAttendances(Long academicProgramId, Long sessionId);

    AttendancePatchResponse correctAttendances(
            Long academicProgramId,
            Long sessionId,
            AttendancePatchRequest request,
            MemberEntity requester);
}
