package org.sscc.ssccopsserver.domain.operation.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkStatusHistoryEntity;

public interface SubWorkStatusHistoryRepository
        extends JpaRepository<SubWorkStatusHistoryEntity, Long> {

    /*
     * 한 하위 업무의 전이 이력을 일어난 순서대로. OPS-011(상태 전환 이력 조회)이 쓸 정렬이며
     * 지금은 전이가 이력을 제대로 쌓는지 검증하는 데 쓴다. 이력은 조회 전용이라
     * 수정·삭제 메서드를 열지 않는다 (POL-004·AP-09).
     */
    List<SubWorkStatusHistoryEntity> findBySubWorkOrderByChangedAtAsc(SubWorkEntity subWork);
}
