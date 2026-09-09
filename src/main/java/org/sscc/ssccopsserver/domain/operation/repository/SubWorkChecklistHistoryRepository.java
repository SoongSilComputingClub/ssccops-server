package org.sscc.ssccopsserver.domain.operation.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkChecklistHistoryEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkEntity;

public interface SubWorkChecklistHistoryRepository
        extends JpaRepository<SubWorkChecklistHistoryEntity, Long> {

    /*
     * 한 하위 업무의 완료 조건 변경 이력을 일어난 순서대로 (#307). 이력은 조회 전용이라
     * 수정·삭제 메서드를 열지 않는다 (POL-004·AP-09 — SubWorkStatusHistoryRepository와 같다).
     *
     * 같은 시각에 여러 건이 들어갈 수 있으므로(한 요청이 한 건씩만 만들지만 시계를 고정한
     * 테스트에서는 겹친다) 식별자를 2차 정렬로 둔다 — 겹치면 순서가 실행마다 달라진다.
     */
    List<SubWorkChecklistHistoryEntity> findBySubWorkOrderByChangedAtAscIdAsc(
            SubWorkEntity subWork);
}
