package org.sscc.ssccopsserver.domain.academicprogram.service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.academicprogram.code.error.AcademicProgramErrorCode;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AttendancePatchRequest;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AttendancePatchResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AttendanceResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionAttendanceSubmitRequest;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AttendanceEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.SessionEntity;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AttendanceRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/*
 * 출석부 조회·정정 (#137 · 학술관리_API설계.md §3.5).
 *
 * 회차 기록 제출(#135)이 출석을 함께 받는데도 이 경로가 따로 있는 것은, 기록을 다시 낼 수 없는
 * 상태(SUBMITTED — 국장 검토 대기)에서도 출석만은 바로잡아야 하는 시나리오 때문이다. 그래서
 * 두 경로가 통과하는 회차 상태 집합이 다르다(SessionStatus.allowsCorrection).
 *
 * **이 경로는 명단을 바꾸지 않는다.** 요청에 실린 참가자의 체크 값만 갈아 끼우며, 줄을 더하거나
 * 빼지 않는다 — 그것이 PATCH(부분 갱신)의 뜻이고, 출석 대상을 바꾸는 일은 기록 전체를 다시 내는
 * 재제출의 몫이다(#135, 전체 교체). 그래서 회차가 기록된 뒤에 확정된 팀원은 이 요청으로
 * 출석부에 들어오지 못하고, 수정요청 → 재제출을 거쳐야 한다. 그 한계를 감수하는 것은 반대로
 * 열어 두면 "부분 갱신"이 사실은 명단 편집이 되어, 국장이 검토 중인 회차의 출석 대상이 검토
 * 도중 늘어날 수 있기 때문이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AttendanceServiceImpl implements AttendanceService {

    private final AttendanceRepository attendanceRepository;
    private final SessionCorrectionPolicy sessionCorrectionPolicy;

    /*
     * 출석부 조회는 인증만 요구한다 — 팀원도 자기 활동의 출석부를 봐야 하고, 여기에 소유권을
     * 걸면 스터디장 한 사람 말고는 아무도 출석을 확인할 수 없다(회차 상세 조회와 같은 판단).
     */
    @Override
    public List<AttendanceResponse> getAttendances(Long academicProgramId, Long sessionId) {
        sessionCorrectionPolicy.requireAcademicProgramExists(academicProgramId);
        SessionEntity session = sessionCorrectionPolicy.findSession(academicProgramId, sessionId);
        return attendanceRepository.findAllBySessionOrderByIdAsc(session).stream()
                .map(AttendanceResponse::from)
                .toList();
    }

    /*
     * 출석 정정. 검사 순서는 넓은 것부터다 — 활동(404) → 소유권(403) → 회차(404) → 확정 여부
     * (409) → 대상(400). 앞의 넷은 SessionCorrectionPolicy가 인증사진 업로드와 나눠 쓴다.
     *
     * 응답은 정정한 줄만이 아니라 출석부 전체와 집계다(AttendancePatchResponse 주석). 이미 읽어
     * 둔 목록을 그대로 접으므로 질의가 늘지 않는다.
     */
    @Override
    @Transactional
    public AttendancePatchResponse correctAttendances(
            Long academicProgramId,
            Long sessionId,
            AttendancePatchRequest request,
            MemberEntity requester) {
        SessionEntity session =
                sessionCorrectionPolicy.requireCorrectable(academicProgramId, sessionId, requester);

        List<AttendanceEntity> attendances =
                attendanceRepository.findAllBySessionOrderByIdAsc(session);
        applyCorrections(session, attendances, request.attendances());

        return AttendancePatchResponse.of(attendances);
    }

    /*
     * 요청의 줄들을 출석부에 반영한다. 대상은 **그 회차 출석부에 이미 줄이 있는 참가자**뿐이며,
     * 없는 대상과 중복된 대상은 조용히 버리지 않고 400으로 끊는다 — 버리면 화면이 보낸 체크와
     * 저장된 출석부가 어긋난 채로 200이 나가고, 운영자는 고쳤다고 믿는다.
     *
     * 확정 팀원인지를 event_ptcp에 다시 물어보지 않는 것은 출석 행의 존재 자체가 그때 그 검증을
     * 통과했다는 뜻이기 때문이다(#135가 저장 시점에 확인한다). 지금은 취소된 팀원이라도 이미
     * 기록된 지난 회차의 출석은 이력이라 고칠 수 있어야 한다.
     *
     * 요청 줄 수와 무관하게 조회는 위에서 한 번뿐이다 — 줄마다 찾으면 그대로 N+1이다.
     */
    private void applyCorrections(
            SessionEntity session,
            List<AttendanceEntity> attendances,
            List<SessionAttendanceSubmitRequest> requested) {
        Map<Long, AttendanceEntity> byParticipantId = new LinkedHashMap<>();
        for (AttendanceEntity attendance : attendances) {
            byParticipantId.put(attendance.getParticipant().getId(), attendance);
        }

        Set<Long> seen = new LinkedHashSet<>();
        for (SessionAttendanceSubmitRequest row : requested) {
            if (!seen.add(row.eventPtcpId())) {
                log.warn(
                        "출석 정정 대상 중복. sessionId={}, eventPtcpId={}",
                        session.getId(),
                        row.eventPtcpId());
                throw new GeneralException(AcademicProgramErrorCode.INVALID_ATTENDANCE_TARGET);
            }

            AttendanceEntity attendance = byParticipantId.get(row.eventPtcpId());
            if (attendance == null) {
                log.warn(
                        "출석부에 없는 정정 대상. sessionId={}, eventPtcpId={}",
                        session.getId(),
                        row.eventPtcpId());
                throw new GeneralException(AcademicProgramErrorCode.INVALID_ATTENDANCE_TARGET);
            }
            attendance.changePresent(Boolean.TRUE.equals(row.atndYn()));
        }
    }
}
