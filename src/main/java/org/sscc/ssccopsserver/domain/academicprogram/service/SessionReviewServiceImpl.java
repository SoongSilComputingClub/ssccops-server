package org.sscc.ssccopsserver.domain.academicprogram.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.academicprogram.code.error.AcademicProgramErrorCode;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionCrossCondition;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionCrossListResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionCrossSearchResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionCursor;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionReviewCondition;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionSearchQuery;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionTransitionRequest;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionTransitionResponse;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramApprovalEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.SessionEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.SessionStatus;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramApprovalRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AttendanceRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.SessionAttendanceCount;
import org.sscc.ssccopsserver.domain.academicprogram.repository.SessionRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.PageResponse;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;

/*
 * 회차 승인·활동 횡단 조회(#136). 세 메서드 모두 ACADEMIC_PROGRAM_MANAGE 전용이며 그 판정은
 * 컨트롤러의 클래스 레벨 @RequireAuthority가 이미 끝냈다 — 여기에는 소유권 정책이 없다
 * (활동을 가로지르는 화면이라 활동별 leadrMbrId 비교로는 애초에 표현되지 않는다).
 *
 * 두 목록은 질의 조립을 공유하고 무엇을 고정하느냐만 다르다(승인 대기 = SUBMITTED 고정).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SessionReviewServiceImpl implements SessionReviewService {

    private final AcademicProgramRepository academicProgramRepository;
    private final SessionRepository sessionRepository;
    private final AttendanceRepository attendanceRepository;
    private final AcademicProgramApprovalRepository academicProgramApprovalRepository;
    private final Clock clock;

    /*
     * 승인·수정요청. 검사 순서는 넓은 것부터다 — 활동(404) → 회차(404) → 전이 가능 여부(409) →
     * 사유(400). 회차를 경로의 활동으로 좁혀 찾는 것은 #135의 조회·재제출과 같은 이유다
     * (식별자만 보면 다른 활동의 회차를 승인할 수 있다).
     *
     * 상태를 바꾸는 것과 승인 이력을 남기는 것은 나눌 수 없는 한 건이라 한 트랜잭션이다 —
     * 이력이 실패하면 상태 변경도 되돌아간다(회원 등급·상태 변경 #78과 같은 원칙). 그래서
     * 수정요청의 사유가 어디에도 남지 않은 채 상태만 REVISION_REQUESTED가 되는 회차는 없다.
     */
    @Override
    @Transactional
    public SessionTransitionResponse transitionSession(
            Long academicProgramId,
            Long sessionId,
            SessionTransitionRequest request,
            MemberEntity approver) {
        requireAcademicProgramExists(academicProgramId);
        SessionEntity session = findSession(sessionId, academicProgramId);

        SessionStatus before = session.getStatus();
        session.changeStatus(request.transition(), request.reason());

        academicProgramApprovalRepository.save(
                AcademicProgramApprovalEntity.forSession(
                        session.getCurriculumItem().getAcademicProgram(),
                        session,
                        request.transition(),
                        approver,
                        request.reason(),
                        Instant.now(clock)));

        return SessionTransitionResponse.of(session.getId(), before, session.getStatus());
    }

    @Override
    public SessionCrossSearchResponse searchCrossSessions(SessionCrossCondition condition) {
        return search(condition.toQuery());
    }

    @Override
    public SessionCrossSearchResponse searchPendingSessions(SessionReviewCondition condition) {
        return search(condition.toQuery());
    }

    /*
     * 두 목록의 공통 몸통. 질의는 목록 · 필터 건수 · 전체 건수 · 출석 집계 넷이며, 회차가 몇
     * 건이든 활동이 몇 개든 이 수는 변하지 않는다(DB-13).
     *
     * overallCount는 필터와 무관한 분모라 회차 전체 건수다 — 승인 대기 목록에서도 '대기 3건 ·
     * 전체 40건'으로 읽힌다. 여기에 SUBMITTED 건수를 넣으면 totalCount와 늘 같은 값이 되어
     * 분모가 아무것도 알려주지 않는다.
     */
    private SessionCrossSearchResponse search(SessionSearchQuery query) {
        // 다음 페이지가 있는지 알기 위해 한 건 더 읽어 왔으므로, 남는 한 건은 응답에서 덜어낸다
        List<SessionEntity> fetched = sessionRepository.searchCross(query);
        boolean hasNext = fetched.size() > query.size();
        List<SessionEntity> rows = hasNext ? fetched.subList(0, query.size()) : fetched;

        Map<Long, SessionAttendanceCount> counts = attendanceCountsOf(rows);
        List<SessionCrossListResponse> sessions =
                rows.stream()
                        .map(
                                session ->
                                        SessionCrossListResponse.of(
                                                session, counts.get(session.getId())))
                        .toList();

        PageResponse page =
                new PageResponse(
                        query.size(),
                        query.sort().getParameter(),
                        nextCursorOf(query, rows, hasNext),
                        hasNext,
                        sessionRepository.countMatching(query),
                        sessionRepository.count());
        return new SessionCrossSearchResponse(sessions, page);
    }

    // 회차가 한 건도 없으면 집계 질의를 보내지 않는다 — in ()은 DB마다 해석이 갈린다
    private Map<Long, SessionAttendanceCount> attendanceCountsOf(List<SessionEntity> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        List<Long> sessionIds = rows.stream().map(SessionEntity::getId).toList();
        return attendanceRepository.countBySessionIds(sessionIds).stream()
                .collect(Collectors.toMap(SessionAttendanceCount::getSessionId, count -> count));
    }

    // 다음 커서는 이번 페이지의 마지막 행을 가리킨다. 마지막 페이지면 커서가 없다
    private String nextCursorOf(
            SessionSearchQuery query, List<SessionEntity> rows, boolean hasNext) {
        return hasNext ? SessionCursor.of(query.sort(), rows.get(rows.size() - 1)).encode() : null;
    }

    /*
     * 활동 존재 여부를 회차보다 먼저 확인한다 — 없는 활동에 회차 404를 돌려주면 화면이
     * "활동이 사라진 것"과 "회차가 없는 것"을 구별하지 못한다(#135의 조회와 같은 태도).
     */
    private void requireAcademicProgramExists(Long academicProgramId) {
        if (!academicProgramRepository.existsById(academicProgramId)) {
            throw new GeneralException(AcademicProgramErrorCode.ACADEMIC_PROGRAM_NOT_FOUND);
        }
    }

    private SessionEntity findSession(Long sessionId, Long academicProgramId) {
        return sessionRepository
                .findByIdAndAcademicProgramId(sessionId, academicProgramId)
                .orElseThrow(
                        () -> new GeneralException(AcademicProgramErrorCode.SESSION_NOT_FOUND));
    }
}
