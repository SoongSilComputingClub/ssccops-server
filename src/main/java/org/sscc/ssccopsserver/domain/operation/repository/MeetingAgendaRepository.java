package org.sscc.ssccopsserver.domain.operation.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.sscc.ssccopsserver.domain.operation.entity.AgendaProcessStatus;
import org.sscc.ssccopsserver.domain.operation.entity.MeetingAgendaEntity;
import org.sscc.ssccopsserver.domain.operation.entity.MeetingEntity;

public interface MeetingAgendaRepository extends JpaRepository<MeetingAgendaEntity, Long> {

    // 회의 상세(OPS-025)·안건 목록(OPS-027)이 연결 운영 건 제목·제출자 이름까지 함께 내려야 하므로 연관을 한 번에 끌어온다 (DB-13)
    @EntityGraph(attributePaths = {"operation", "submitter"})
    List<MeetingAgendaEntity> findAllByMeetingOrderByAgendaOrderAsc(MeetingEntity meeting);

    /*
     * 경로의 두 식별자(회의·안건)를 함께 걸러 찾는다 — 안건 식별자만 보면 남의 회의에 속한
     * 안건도 그대로 노출된다(폼 응답의 findByIdAndForm과 같은 이유).
     */
    Optional<MeetingAgendaEntity> findByIdAndMeeting(Long id, MeetingEntity meeting);

    // 회의 종료 전이(TR-M3)가 미처리(PENDING) 안건 존재만 확인하면 되므로 목록 대신 건수만 센다
    boolean existsByMeetingAndProcessStatus(
            MeetingEntity meeting, AgendaProcessStatus processStatus);

    // 새 안건의 표시 순서(agnd_seq)는 이미 있는 안건의 마지막 순서 다음이다 — 중간 삭제로 순서에 빈틈이 생겨도 뒤로만 붙는다
    Optional<MeetingAgendaEntity> findTopByMeetingOrderByAgendaOrderDesc(MeetingEntity meeting);

    /*
     * 회의 목록(GET /v1/meetings)의 카드별 안건 건수. 회의마다 안건을 세면 N+1이 되므로
     * 이번 페이지의 회의들에 대해 한 번에 집계한다 (DB-13, SubWorkChecklistItemRepository 선례).
     * 안건이 한 건도 없는 회의는 GROUP BY 결과에 나오지 않는다 — 그 경우를 0건으로 보는 것은
     * 호출부(MeetingServiceImpl)의 몫이다.
     */
    @Query(
            "select a.meeting.id as meetingId, count(a) as agendaCount"
                    + " from MeetingAgendaEntity a"
                    + " where a.meeting.id in :meetingIds"
                    + " group by a.meeting.id")
    List<MeetingAgendaCount> countGroupedByMeetingIds(
            @Param("meetingIds") Collection<Long> meetingIds);
}
