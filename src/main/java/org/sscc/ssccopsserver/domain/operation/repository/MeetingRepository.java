package org.sscc.ssccopsserver.domain.operation.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.operation.entity.MeetingEntity;

public interface MeetingRepository extends JpaRepository<MeetingEntity, Long> {

    /*
     * 소프트 삭제 여부는 부모 oper가 관리하므로 조인해서 걸러낸다. mtg 자체에는 del_dt가 없다.
     *
     * 상세 조회(OPS-025)가 책임자·등록자를 이름까지 내려야 하므로 연관을 한 번에 끌어온다 —
     * LAZY 그대로 두면 응답 조립 단계에서 연관마다 쿼리가 더 나간다 (DB-13, WorkRepository 선례).
     */
    @EntityGraph(
            attributePaths = {
                "operation",
                "operation.personInCharge",
                "operation.registrant",
                "responsiblePerson"
            })
    Optional<MeetingEntity> findByIdAndOperationDeletedAtIsNull(Long id);

    /*
     * 회의 목록 조회. 정의서에 목록 API가 없어 결번을 새로 부여한 엔드포인트다(WorkController의
     * OPS-020 선례). 시안이 카드 그리드 하나로 페이징 없이 전량을 보여주므로 커서 페이징을
     * 쓰지 않는다 — 회의는 동아리 규모상 폭증할 자료가 아니다.
     */
    @EntityGraph(attributePaths = {"operation", "operation.personInCharge", "responsiblePerson"})
    List<MeetingEntity> findAllByOperationDeletedAtIsNullOrderByOperationCreatedAtDesc();
}
