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

    /*
     * 완료 승인 전이(TR-03)의 선행 조건 판정용. 남은 항목이 0건이어야 완료할 수 있다.
     * 항목 전체를 로딩해 세지 않는다 — 판정에 필요한 것은 개수뿐이다.
     * 유형에 완료 점검 항목이 없어 체크리스트가 비어 있으면 0건이라 그대로 통과한다.
     */
    long countBySubWorkAndCompletedFalse(SubWorkEntity subWork);
}
