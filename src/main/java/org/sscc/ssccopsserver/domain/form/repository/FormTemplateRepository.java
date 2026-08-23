package org.sscc.ssccopsserver.domain.form.repository;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.form.entity.FormTemplateEntity;

/*
 * 폼 템플릿 조회 (#142).
 *
 * 정렬을 쿼리에 고정하는 이유는 FormLabelRepository와 같다 — 화면에 정렬 기준이 없어 서버가
 * 정해야 하고, 이름 오름차순이면 템플릿이 늘어도 같은 자리에 있다.
 *
 * 두 조회 모두 @EntityGraph로 생성자를 함께 끌어온다. 목록이 creatrMbrNm을 싣는데
 * creator가 LAZY라 그대로 두면 템플릿 수만큼 회원 조회가 따라붙는다(DB-13). 템플릿은 운영진이
 * 손으로 만드는 데이터라 수십 건을 넘지 않지만, N+1은 건수가 적어서 괜찮은 종류의 문제가 아니다.
 */
public interface FormTemplateRepository extends JpaRepository<FormTemplateEntity, Long> {

    /*
     * 관리 화면의 목록. 비활성 템플릿도 함께 보여주므로 여기서는 거르지 않는다 —
     * 관리 화면과 '템플릿에서 시작하기' 화면이 보는 집합이 다르다는 것이 use_yn의 존재 이유다.
     */
    @EntityGraph(attributePaths = "creator")
    List<FormTemplateEntity> findAllByOrderByNameAsc();

    /** 활성/비활성 어느 쪽으로도 거를 수 있게 값을 받는 형태. ?useYn=false(비활성만)도 같은 메서드로 답한다 */
    @EntityGraph(attributePaths = "creator")
    List<FormTemplateEntity> findAllByActiveOrderByNameAsc(boolean active);
}
