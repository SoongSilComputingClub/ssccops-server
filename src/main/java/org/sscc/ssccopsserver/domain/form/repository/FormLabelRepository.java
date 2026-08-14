package org.sscc.ssccopsserver.domain.form.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.form.entity.FormLabelEntity;

/*
 * 폼 라벨 조회. 라벨 관리 API(#34)가 쓸 시그니처를 미리 잡아 둔다.
 */
public interface FormLabelRepository extends JpaRepository<FormLabelEntity, Long> {

    /*
     * 필터·지정 화면에 뿌릴 라벨 목록. 비활성 라벨은 새로 달 수 없으므로 여기서 빠진다 —
     * 이미 달린 라벨은 form_lbl_rel을 통해 그대로 보인다.
     *
     * 정렬을 쿼리에 고정하는 이유는 화면에 정렬 기준이 없어 서버가 정해야 하기 때문이다.
     * 이름 오름차순이면 라벨이 늘어도 같은 자리에 있다.
     */
    List<FormLabelEntity> findAllByActiveTrueOrderByNameAsc();

    /*
     * 라벨 관리 화면(#34)의 목록. 비활성 라벨도 취소선으로 함께 보여주므로 여기서는 거르지 않는다 —
     * 관리 화면과 지정·필터 화면이 보는 집합이 다르다는 것이 use_yn의 존재 이유다.
     */
    List<FormLabelEntity> findAllByOrderByNameAsc();

    /** 활성/비활성 어느 쪽으로도 거를 수 있게 값을 받는 형태. ?useYn=false(비활성만)도 같은 메서드로 답한다 */
    List<FormLabelEntity> findAllByActiveOrderByNameAsc(boolean active);

    /*
     * 이름 중복 확인용. uk_form_lbl_name이 최종 방어선이라 이 확인이 없어도 데이터는 깨지지 않지만,
     * 정상 경로에서 DataIntegrityViolationException으로 실패를 알리는 것보다 낫다 (#21 선례).
     */
    Optional<FormLabelEntity> findByName(String name);

    boolean existsByName(String name);
}
