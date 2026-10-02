package org.sscc.ssccopsserver.domain.event.repository;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.event.entity.EventParticipantStatusHistoryEntity;

public interface EventParticipantStatusHistoryRepository
        extends JpaRepository<EventParticipantStatusHistoryEntity, Long> {

    /*
     * 한 행사의 참가 상태 이력, 최신순 (#612 · 학술 팀원 이력 GET .../members/history).
     *
     * 줄마다 참가자 이름과 수행자 이름을 내리므로 함께 끌어온다 — LAZY 그대로 두면 이력 줄 수의
     * 두 배만큼 조회가 더 나간다(DB-13). 정렬이 식별자인 것은 같은 요청 안에서 여러 줄이 같은
     * chg_dt로 찍힐 수 있어서다(모집 선발 한 번이 여러 명을 바꾼다).
     */
    @EntityGraph(attributePaths = {"participant", "participant.member", "performer"})
    List<EventParticipantStatusHistoryEntity> findAllByParticipantEventOrderByIdDesc(
            EventEntity event);
}
