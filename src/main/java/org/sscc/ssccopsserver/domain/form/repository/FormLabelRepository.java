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
     * 이름 중복 확인용. 라벨 이름에 UNIQUE를 걸지 않은 것은 데이터사전을 따른 것이라,
     * 중복 판단은 애플리케이션이 한다. 동시 생성까지 막아야 한다면 그때 제약을 추가한다 (#34).
     */
    Optional<FormLabelEntity> findByName(String name);

    boolean existsByName(String name);
}
