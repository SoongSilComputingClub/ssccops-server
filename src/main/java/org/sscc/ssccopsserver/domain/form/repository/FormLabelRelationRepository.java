package org.sscc.ssccopsserver.domain.form.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormLabelEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormLabelRelationEntity;

/*
 * 폼-라벨 연결 조회·해제. FormEntity가 라벨 컬렉션을 들고 있지 않으므로(폼 목록의 N+1을
 * 피하기 위한 결정) 라벨은 항상 이 리포지토리를 통해 모아 온다.
 */
public interface FormLabelRelationRepository extends JpaRepository<FormLabelRelationEntity, Long> {

    /** 폼 상세(#32)의 라벨 목록. 라벨 이름이 필요하므로 연관을 함께 끌어온다 */
    @EntityGraph(attributePaths = "label")
    List<FormLabelRelationEntity> findAllByForm(FormEntity form);

    /*
     * 폼 목록(#32)의 라벨 일괄 조회. 폼마다 findAllByForm을 부르면 그대로 N+1이 되므로
     * 목록에 뜬 폼 식별자 전부를 한 번에 넘겨 받아 호출부에서 폼별로 나눈다 (DB-13).
     */
    @EntityGraph(attributePaths = {"form", "label"})
    List<FormLabelRelationEntity> findAllByFormIdIn(Collection<Long> formIds);

    /** 라벨별 폼 목록 필터(#34)에 쓸 역방향 조회 */
    @EntityGraph(attributePaths = "form")
    List<FormLabelRelationEntity> findAllByLabel(FormLabelEntity label);

    /*
     * 중복 연결 선조회. UNIQUE 제약이 최종 방어선이므로 이 확인이 없어도 데이터는 깨지지 않지만,
     * 정상 경로에서 DataIntegrityViolationException으로 실패를 알리는 것보다 낫다 (#21 선례).
     */
    boolean existsByFormAndLabel(FormEntity form, FormLabelEntity label);

    /** 라벨 떼기(#34). 연결은 되돌릴 수 있는 행위라 소프트 삭제하지 않고 지운다 */
    long deleteByFormAndLabel(FormEntity form, FormLabelEntity label);
}
