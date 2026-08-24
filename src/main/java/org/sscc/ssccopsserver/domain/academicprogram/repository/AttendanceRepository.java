package org.sscc.ssccopsserver.domain.academicprogram.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AttendanceEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.SessionEntity;

public interface AttendanceRepository extends JpaRepository<AttendanceEntity, Long> {

    /*
     * 회차 상세(#135)의 출석부. 회원명을 attendance에 복사하지 않고 조인해 싣기 때문에
     * 참가자·회원을 함께 끌어온다 — LAZY 그대로 두면 출석 한 줄마다 조회가 두 번씩 더 나간다
     * (DB-13 · EventParticipantRepository.findAllByEventAndStatusInOrderByIdAsc와 같은 이유).
     *
     * 정렬은 등록 순번(식별자 오름차순)이다. 출석부 줄 순서가 요청마다 흔들리면 체크리스트로
     * 쓸 수 없다.
     */
    @EntityGraph(attributePaths = {"participant", "participant.member"})
    List<AttendanceEntity> findAllBySessionOrderByIdAsc(SessionEntity session);

    /*
     * 회차별 출석 집계(#135 목록의 presentCount/totalCount). 회차가 몇 건이든 질의는 하나다
     * (DB-13 · EventParticipantRepository.countByEventIds 선례).
     *
     * 출석 행이 하나도 없는 회차는 GROUP BY 결과에 나오지 않는다 — 0/0으로 채우는 것은 호출부다.
     */
    @Query(
            "select s.id as sessionId, count(a) as totalCount,"
                    + " sum(case when a.present = true then 1L else 0L end) as presentCount"
                    + " from AttendanceEntity a join a.session s"
                    + " where s.id in :sessionIds"
                    + " group by s.id")
    List<SessionAttendanceCount> countBySessionIds(
            @Param("sessionIds") Collection<Long> sessionIds);
}
