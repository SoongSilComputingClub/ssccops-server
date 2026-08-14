package org.sscc.ssccopsserver.domain.operation.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkTypeEntity;

public interface SubWorkTypeRepository extends JpaRepository<SubWorkTypeEntity, Long> {

    /*
     * 관리 화면(OPS-SCR-006)은 필터 없이, 등록 폼 드롭다운(OPS-SCR-005)은 활성만 부른다.
     * 정렬을 식별자 오름차순으로 고정하는 것은 화면에 정렬 규칙이 없어서다 — 고정하지 않으면
     * 유형을 하나 고칠 때마다 목록 순서가 흔들린다.
     */
    List<SubWorkTypeEntity> findAllByOrderByIdAsc();

    List<SubWorkTypeEntity> findAllByActiveOrderByIdAsc(boolean active);

    boolean existsByTypeName(String typeName);

    boolean existsByTypeNameAndIdNot(String typeName, Long id);
}
