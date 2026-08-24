package org.sscc.ssccopsserver.domain.event.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.sscc.ssccopsserver.domain.event.code.EventParticipantStatus;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.event.entity.EventParticipantEntity;

public interface EventParticipantRepository extends JpaRepository<EventParticipantEntity, Long> {

    /*
     * 행사 삭제 가드(D9 · EVENT_HAS_PARTICIPANT)와 폼 연결 변경 가드(D11 · EVENT_FORM_IN_USE)의
     * 판단 근거. 상태를 가리지 않는다 — 취소(CANCELLED)된 참가자도 명단에 영구 보존되는
     * 이력(D16)이라, 취소만 남은 행사라고 지워도 되는 것은 아니다.
     */
    boolean existsByEvent(EventEntity event);

    /*
     * 행사별 참가자 수 일괄 집계 (ssccops#139 목록·상세의 confirmedCount). 행사가 몇 건이든
     * 질의는 하나다 (DB-13 · FormResponseHistoryRepository.countByFormIds 선례).
     *
     * 어떤 상태를 셀지는 호출부가 정한다 — confirmedCount는 CONFIRMED만 센다. 대기·취소는
     * 정원 대비 확정 인원을 보는 값에 들어가면 화면의 "N/정원"이 부푼다.
     */
    @Query(
            "select e.id as eventId, count(p) as confirmedCount"
                    + " from EventParticipantEntity p join p.event e"
                    + " where e.id in :eventIds and p.status = :status"
                    + " group by e.id")
    List<EventParticipantCount> countByEventIds(
            @Param("eventIds") Collection<Long> eventIds,
            @Param("status") EventParticipantStatus status);
}
