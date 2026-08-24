package org.sscc.ssccopsserver.domain.academicprogram.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.academicprogram.code.error.AcademicProgramErrorCode;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionAttendanceSubmitRequest;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionCondition;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionCursor;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionDetailResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionSearchQuery;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionSearchResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionSubmitRequest;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionSummaryResponse;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramApprovalPoint;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AttendanceEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.CurriculumItemEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.SessionEntity;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramApprovalRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AttendanceRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.CurriculumItemRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.SessionAttendanceCount;
import org.sscc.ssccopsserver.domain.academicprogram.repository.SessionRepository;
import org.sscc.ssccopsserver.domain.event.code.EventParticipantStatus;
import org.sscc.ssccopsserver.domain.event.entity.EventParticipantEntity;
import org.sscc.ssccopsserver.domain.event.repository.EventParticipantRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.PageResponse;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/*
 * 회차 실적 기록·조회(#135). 쓰기 두 경로(제출·재제출)만 소유권 정책을 태우고 조회 둘은 인증만
 * 요구한다 — 팀원도 자기 활동의 회차를 봐야 한다(학술관리_API설계.md §3.4).
 *
 * 출석은 별도 요청이 아니라 이 트랜잭션 안에서 함께 저장된다. 화면이 진행 내용과 출석 체크를
 * 한 화면·단일 제출 버튼으로 받기 때문이고, 나눠 두면 "내용은 저장됐는데 출석은 안 된" 회차가
 * 만들어진다.
 *
 * 승인 이력(academic_program_aprv)에 행을 남기지 않는다. 제출은 검토를 기다리는 상태
 * (session_stts_cd = SUBMITTED)일 뿐이고, 그 테이블에 무엇을 언제 남길지는 회차 승인(#136)의
 * 몫이다 — 이 서비스는 그 최신 행의 사유를 읽기만 한다(latestOpinion).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SessionServiceImpl implements SessionService {

    /*
     * 출석 대상은 확정 팀원뿐이다(설계 결정 #3). 대기자는 아직 팀원이 아니고, 취소자는 더 이상
     * 팀원이 아니다 — 이미 저장된 지난 회차의 출석 행은 그대로 남지만(이력) 새 기록에는 실을 수
     * 없다.
     */
    private static final Set<EventParticipantStatus> ATTENDANCE_TARGET_STATUSES =
            Set.of(EventParticipantStatus.CONFIRMED);

    private final AcademicProgramRepository academicProgramRepository;
    private final CurriculumItemRepository curriculumItemRepository;
    private final SessionRepository sessionRepository;
    private final AttendanceRepository attendanceRepository;
    private final AcademicProgramApprovalRepository academicProgramApprovalRepository;
    private final EventParticipantRepository eventParticipantRepository;
    private final AcademicProgramOwnershipPolicy academicProgramOwnershipPolicy;

    /*
     * 신규 제출. 검사 순서는 넓은 것부터다 — 활동(404) → 소유권(403) → 계획 항목(404) →
     * 중복(409) → 출석 대상(400). 소유권을 계획 항목보다 먼저 보는 것은 남의 활동에 대해
     * 커리큘럼 번호를 바꿔 가며 부르는 것만으로 몇 번 회차가 있는지 알아낼 수 없게 하기
     * 위해서다.
     */
    @Override
    @Transactional
    public SessionDetailResponse submitSession(
            Long academicProgramId, SessionSubmitRequest request, MemberEntity requester) {
        AcademicProgramEntity academicProgram = findAcademicProgram(academicProgramId);
        academicProgramOwnershipPolicy.requireLeader(academicProgram, requester);

        CurriculumItemEntity curriculumItem =
                findCurriculumItem(request.curriculumItemId(), academicProgramId);
        if (sessionRepository.existsByCurriculumItemId(curriculumItem.getId())) {
            throw new GeneralException(AcademicProgramErrorCode.SESSION_ALREADY_EXISTS);
        }

        List<AttendanceRow> rows = resolveAttendances(academicProgram, request);

        SessionEntity session =
                saveSession(
                        SessionEntity.submit(
                                curriculumItem,
                                request.realDt(),
                                request.cn(),
                                request.noticeCn(),
                                requester));
        attendanceRepository.saveAll(
                rows.stream()
                        .map(row -> AttendanceEntity.of(session, row.participant(), row.present()))
                        .toList());

        return detailOf(session);
    }

    /*
     * 재제출. 이전 내용을 덮어쓰고 이력을 남기지 않는다(데이터모델 §7) — 그래서 여기에는
     * "직전 제출"을 어디로 옮겨 두는 코드가 없고, 있어서도 안 된다.
     *
     * 쓸 수 있는 상태인지를 계획 항목·출석 대상 검증보다 먼저 본다(SessionEntity.
     * requireResubmittable 주석) — 애초에 성립하지 않는 재제출에 엉뚱한 400이 먼저 붙지 않게
     * 하기 위해서다.
     */
    @Override
    @Transactional
    public SessionDetailResponse resubmitSession(
            Long academicProgramId,
            Long sessionId,
            SessionSubmitRequest request,
            MemberEntity requester) {
        AcademicProgramEntity academicProgram = findAcademicProgram(academicProgramId);
        academicProgramOwnershipPolicy.requireLeader(academicProgram, requester);

        SessionEntity session = findSession(sessionId, academicProgramId);
        session.requireResubmittable();

        CurriculumItemEntity curriculumItem =
                findCurriculumItem(request.curriculumItemId(), academicProgramId);
        requireVacantWhenMoved(session, curriculumItem);

        List<AttendanceRow> rows = resolveAttendances(academicProgram, request);

        session.resubmit(
                curriculumItem, request.realDt(), request.cn(), request.noticeCn(), requester);
        replaceAttendances(session, rows);

        return detailOf(session);
    }

    @Override
    public SessionDetailResponse getSession(Long academicProgramId, Long sessionId) {
        requireAcademicProgramExists(academicProgramId);
        return detailOf(findSession(sessionId, academicProgramId));
    }

    /*
     * 목록. 질의는 목록 · 필터 건수 · 활동 전체 건수 · 출석 집계 넷이며, 회차가 몇 건이든 이
     * 수는 변하지 않는다(DB-13).
     */
    @Override
    public SessionSearchResponse searchSessions(
            Long academicProgramId, SessionCondition condition) {
        requireAcademicProgramExists(academicProgramId);
        SessionSearchQuery query = condition.toQuery(academicProgramId);

        // 다음 페이지가 있는지 알기 위해 한 건 더 읽어 왔으므로, 남는 한 건은 응답에서 덜어낸다
        List<SessionEntity> fetched = sessionRepository.search(query);
        boolean hasNext = fetched.size() > query.size();
        List<SessionEntity> rows = hasNext ? fetched.subList(0, query.size()) : fetched;

        Map<Long, SessionAttendanceCount> counts = attendanceCountsOf(rows);
        List<SessionSummaryResponse> sessions =
                rows.stream()
                        .map(
                                session ->
                                        SessionSummaryResponse.of(
                                                session, counts.get(session.getId())))
                        .toList();

        PageResponse page =
                new PageResponse(
                        query.size(),
                        query.sort().getParameter(),
                        nextCursorOf(query, rows, hasNext),
                        hasNext,
                        sessionRepository.countMatching(query),
                        sessionRepository.countByAcademicProgramId(academicProgramId));
        return new SessionSearchResponse(sessions, page);
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
     * 요청의 출석 목록을 확정 팀원 행으로 해석한다. 대상이 아닌 참가자·중복된 참가자는 여기서
     * 400으로 끊는다 — 조용히 버리거나 접으면 화면이 보낸 명단과 저장된 출석부가 어긋난다.
     *
     * 확정 팀원은 요청 줄 수와 무관하게 한 번만 읽는다(참가자 명단 조회를 재사용한다) —
     * 줄마다 조회하면 그대로 N+1이다.
     */
    private List<AttendanceRow> resolveAttendances(
            AcademicProgramEntity academicProgram, SessionSubmitRequest request) {
        List<SessionAttendanceSubmitRequest> requested = request.attendancesOrEmpty();
        if (requested.isEmpty()) {
            return List.of();
        }

        Set<Long> requestedIds = new LinkedHashSet<>();
        for (SessionAttendanceSubmitRequest attendance : requested) {
            if (!requestedIds.add(attendance.eventPtcpId())) {
                log.warn(
                        "출석 대상 중복. academicProgramId={}, eventPtcpId={}",
                        academicProgram.getId(),
                        attendance.eventPtcpId());
                throw new GeneralException(AcademicProgramErrorCode.INVALID_ATTENDANCE_TARGET);
            }
        }

        Map<Long, EventParticipantEntity> confirmed =
                eventParticipantRepository
                        .findAllByEventAndStatusInOrderByIdAsc(
                                academicProgram.getEvent(), ATTENDANCE_TARGET_STATUSES)
                        .stream()
                        .collect(
                                Collectors.toMap(
                                        EventParticipantEntity::getId, Function.identity()));

        List<AttendanceRow> rows = new ArrayList<>(requested.size());
        for (SessionAttendanceSubmitRequest attendance : requested) {
            EventParticipantEntity participant = confirmed.get(attendance.eventPtcpId());
            if (participant == null) {
                log.warn(
                        "확정 팀원이 아닌 출석 대상. academicProgramId={}, eventPtcpId={}",
                        academicProgram.getId(),
                        attendance.eventPtcpId());
                throw new GeneralException(AcademicProgramErrorCode.INVALID_ATTENDANCE_TARGET);
            }
            rows.add(new AttendanceRow(participant, Boolean.TRUE.equals(attendance.presentYn())));
        }
        return rows;
    }

    /*
     * 출석 교체는 통째로 지웠다 넣지 않고 차집합만 움직인다(FormLabelServiceImpl.
     * replaceFormLabels와 같은 이유) — 같은 (session, event_ptcp) 쌍을 한 트랜잭션에서 지웠다
     * 넣으면 Hibernate가 INSERT를 DELETE보다 먼저 흘려보내 uk_attendance_session_participant에
     * 걸린다. 남는 줄은 체크 값만 갈아 끼운다.
     */
    private void replaceAttendances(SessionEntity session, List<AttendanceRow> rows) {
        Map<Long, AttendanceEntity> existing =
                attendanceRepository.findAllBySessionOrderByIdAsc(session).stream()
                        .collect(
                                Collectors.toMap(
                                        attendance -> attendance.getParticipant().getId(),
                                        attendance -> attendance,
                                        (first, second) -> first,
                                        LinkedHashMap::new));

        List<AttendanceEntity> added = new ArrayList<>();
        for (AttendanceRow row : rows) {
            AttendanceEntity kept = existing.remove(row.participant().getId());
            if (kept == null) {
                added.add(AttendanceEntity.of(session, row.participant(), row.present()));
            } else {
                kept.changePresent(row.present());
            }
        }

        // remove로 훑고 남은 것이 이번 요청에 없는 출석이다
        if (!existing.isEmpty()) {
            attendanceRepository.deleteAllInBatch(existing.values());
        }
        if (!added.isEmpty()) {
            attendanceRepository.saveAll(added);
        }
    }

    /*
     * 재제출이 회차를 옮기는 경우에만 대상 자리가 비었는지 본다. 옮기는 것을 허용하는 것은
     * 재제출이 전체 교체이기 때문이다 — 회차를 잘못 골라 기록한 것을 바로잡을 길이 이 요청밖에
     * 없다. 다만 옮겨 갈 자리에 이미 실적이 있으면 신규 제출과 같은 409다(계획 1개당 실적 1개).
     */
    private void requireVacantWhenMoved(SessionEntity session, CurriculumItemEntity target) {
        if (session.getCurriculumItem().getId().equals(target.getId())) {
            return;
        }
        if (sessionRepository.existsByCurriculumItemId(target.getId())) {
            throw new GeneralException(AcademicProgramErrorCode.SESSION_ALREADY_EXISTS);
        }
    }

    /*
     * 선조회(existsByCurriculumItemId)만으로는 동시 제출을 막지 못하므로 UNIQUE 위반도 같은
     * 409로 옮긴다 — 회원가입의 학번 중복, 폼 응답의 중복 제출과 같은 처리다. flush를 여기서
     * 부르는 것은 트랜잭션이 끝날 때 터지면 컨트롤러 밖이라 코드로 옮길 자리가 없기 때문이다.
     */
    private SessionEntity saveSession(SessionEntity session) {
        try {
            return sessionRepository.saveAndFlush(session);
        } catch (DataIntegrityViolationException ex) {
            log.warn("회차 실적 동시 제출. curriculumItemId={}", session.getCurriculumItem().getId(), ex);
            throw new GeneralException(AcademicProgramErrorCode.SESSION_ALREADY_EXISTS);
        }
    }

    private SessionDetailResponse detailOf(SessionEntity session) {
        List<AttendanceEntity> attendances =
                attendanceRepository.findAllBySessionOrderByIdAsc(session);
        return SessionDetailResponse.of(session, attendances, latestOpinionOf(session));
    }

    /*
     * 마지막 검토 의견. 회차 승인(#136)이 academic_program_aprv에 SESSION 행을 남기기 전까지는
     * 언제나 null이다 — 값을 만들어 내지 않고 없으면 없는 대로 내린다.
     */
    private String latestOpinionOf(SessionEntity session) {
        return academicProgramApprovalRepository
                .findFirstBySessionIdAndPointOrderByIdDesc(
                        session.getId(), AcademicProgramApprovalPoint.SESSION)
                .map(approval -> approval.getOpinionContent())
                .orElse(null);
    }

    private AcademicProgramEntity findAcademicProgram(Long academicProgramId) {
        return academicProgramRepository
                .findById(academicProgramId)
                .orElseThrow(
                        () ->
                                new GeneralException(
                                        AcademicProgramErrorCode.ACADEMIC_PROGRAM_NOT_FOUND));
    }

    /*
     * 조회 경로는 활동 엔티티가 필요 없지만 존재 여부는 확인한다 — 없는 활동에 빈 목록이나
     * 회차 404를 돌려주면 화면이 "활동이 사라진 것"과 "회차가 없는 것"을 구별하지 못한다
     * (계획 조회 #134와 같은 태도).
     */
    private void requireAcademicProgramExists(Long academicProgramId) {
        if (!academicProgramRepository.existsById(academicProgramId)) {
            throw new GeneralException(AcademicProgramErrorCode.ACADEMIC_PROGRAM_NOT_FOUND);
        }
    }

    private CurriculumItemEntity findCurriculumItem(Long curriculumItemId, Long academicProgramId) {
        return curriculumItemRepository
                .findByIdAndAcademicProgramId(curriculumItemId, academicProgramId)
                .orElseThrow(
                        () ->
                                new GeneralException(
                                        AcademicProgramErrorCode.CURRICULUM_ITEM_NOT_FOUND));
    }

    private SessionEntity findSession(Long sessionId, Long academicProgramId) {
        return sessionRepository
                .findByIdAndAcademicProgramId(sessionId, academicProgramId)
                .orElseThrow(
                        () -> new GeneralException(AcademicProgramErrorCode.SESSION_NOT_FOUND));
    }

    /* 검증을 통과한 출석 한 줄. 참가자 조회를 두 번 하지 않으려고 엔티티를 들고 다닌다 */
    private record AttendanceRow(EventParticipantEntity participant, boolean present) {}
}
