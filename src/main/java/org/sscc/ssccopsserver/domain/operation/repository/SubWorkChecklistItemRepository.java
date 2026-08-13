package org.sscc.ssccopsserver.domain.operation.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkChecklistItemEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkEntity;

public interface SubWorkChecklistItemRepository
        extends JpaRepository<SubWorkChecklistItemEntity, Long> {

    /*
     * 상세 조회(OPS-009)의 완료 체크리스트. 화면 표시 순서가 유형에 적힌 항목 순서이므로
     * 정렬을 쿼리에 고정한다 — 클라이언트가 다시 정렬하지 않아도 되게 한다.
     */
    List<SubWorkChecklistItemEntity> findBySubWorkOrderBySortOrderAsc(SubWorkEntity subWork);
}
