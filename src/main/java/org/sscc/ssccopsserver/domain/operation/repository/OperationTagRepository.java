package org.sscc.ssccopsserver.domain.operation.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.operation.entity.OperationTagEntity;

/*
 * 운영 태그 조회 (#637). FormLabelRepository와 같은 모양이되 use_yn이 없어 목록이 하나다.
 */
public interface OperationTagRepository extends JpaRepository<OperationTagEntity, Long> {

    /*
     * 관리·지정·필터 화면이 모두 이 목록을 쓴다. 화면에 정렬 기준이 없어 서버가 정한다 —
     * 이름 오름차순이면 태그가 늘어도 같은 자리에 있다.
     */
    List<OperationTagEntity> findAllByOrderByNameAsc();

    /*
     * 이름 중복 선조회. uk_oper_tag_name이 최종 방어선이라 이것이 없어도 데이터는 깨지지 않지만,
     * 정상 경로에서 DataIntegrityViolationException으로 실패를 알리는 것보다 낫다 (#21 선례).
     */
    boolean existsByName(String name);

    // 이름 변경의 중복 확인 — 자기 자신과 같은 이름으로 «바꾸는» 것은 중복이 아니다
    boolean existsByNameAndIdNot(String name, Long id);
}
