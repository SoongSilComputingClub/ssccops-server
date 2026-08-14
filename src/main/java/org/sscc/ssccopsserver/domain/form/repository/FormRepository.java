package org.sscc.ssccopsserver.domain.form.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
