package org.sscc.ssccopsserver.domain.form.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;

/*
 * 폼 조회. 컨트롤러·서비스는 후속 이슈(#32 폼 CRUD, #35 공개 폼 조회)에서 붙지만,
 * 그 이슈들이 기대는 조회 시그니처는 여기서 미리 잡아 둔다 — 나중에 각자 만들면
 * 같은 조회에 이름이 두 개 생기고 목록 정렬 기준이 화면마다 달라진다.
 */
public interface FormRepository extends JpaRepository<FormEntity, Long> {

    /*
     * 관리자 폼 목록(#32). 상태 필터가 선택 사항이라 상태 집합을 받는 형태로 두었다 —
     * "전체"는 전체 상태를 넣어 부르면 되고, 상태별 메서드를 상태 수만큼 늘리지 않아도 된다.
     *
     * 목록에는 생성자 이름이 필요하므로 연관을 함께 끌어온다. LAZY 그대로 두면 목록 한 줄마다
     * 회원 조회가 한 번씩 더 나간다 (DB-13).
     */
    @EntityGraph(attributePaths = "creator")
    Page<FormEntity> findAllByStatusIn(Collection<FormStatus> statuses, Pageable pageable);

    /*
     * 공개 폼 단건 조회(#35). 작성 중(DRAFT)인 폼은 링크를 알아도 열리면 안 되므로
     * 상태를 조건에 넣어 "없는 것"으로 만든다 — 존재를 알려주지 않기 위해 403으로 나누지 않는다.
     */
    Optional<FormEntity> findByIdAndStatus(Long id, FormStatus status);

    /*
     * 라벨로 거른 폼 목록(#34). 관계 테이블을 지나는 조인이라 파생 쿼리로는 표현이 길어져
     * 연관 경로를 그대로 쓰는 파생 이름 대신 여기서 이름을 고정한다.
     */
    @EntityGraph(attributePaths = "creator")
    List<FormEntity> findAllByIdInOrderByIdDesc(Collection<Long> ids);

    /*
     * 관리자 폼 목록의 실제 조회 (#32 · GET /v1/forms). 상태·라벨 두 필터가 각각 선택이고
     * 둘 다 주면 AND라, 조합마다 파생 메서드를 두면 네 개가 된다. Specification을 쓰지 않은 것은
     * 필터가 두 개로 고정돼 있어 동적 조립의 이득이 없고, JPQL이면 조인·페치 전략이 한눈에
     * 보이기 때문이다.
     *
     * 상태는 집합으로 받아 "전체"를 전체 상태로 표현한다 — 열거형 파라미터에 NULL을 넣고
     * :status is null로 분기하면 Hibernate가 파라미터 타입을 추론하지 못해 방언에 따라 깨진다.
     * 반대로 라벨 식별자는 Long이라 NULL 비교가 안전해 그대로 선택 필터로 둔다.
     *
     * 라벨 필터를 조인이 아니라 EXISTS 하위 질의로 쓴 것은, 한 폼에 라벨이 여러 개 달려 있을 때
     * 조인이 폼을 라벨 수만큼 중복시키기 때문이다. distinct로 지우면 join fetch와 함께 쓸 때
     * 페이징이 메모리로 넘어간다. 생성자는 목록에 필요하므로 함께 페치한다 (DB-13).
     */
    @Query(
            "select f from FormEntity f join fetch f.creator"
                    + " where f.status in :statuses"
                    + " and (:labelId is null or exists ("
                    + "   select 1 from FormLabelRelationEntity r"
                    + "   where r.form = f and r.label.id = :labelId))"
                    + " order by f.id desc")
    List<FormEntity> findAllForAdminList(
            @Param("statuses") Collection<FormStatus> statuses, @Param("labelId") Long labelId);
}
