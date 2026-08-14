package org.sscc.ssccopsserver.domain.operation.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.WorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.WorkStatus;

public interface SubWorkRepository extends JpaRepository<SubWorkEntity, Long> {

    /*
     * 상세 조회(OPS-009)용 단건 조회. 소프트 삭제 여부는 부모 oper가 관리하므로 조인해서
     * 걸러내고, 삭제된 건은 없는 것으로 본다(404).
     *
     * 상세 응답이 담당자·등록자 이름과 유형명·상위 업무 식별자를 모두 쓰므로 연관을 한 번에
     * 끌어온다 — LAZY 그대로 두면 응답 조립 단계에서 연관마다 쿼리가 더 나간다 (DB-13).
     */
    @EntityGraph(
            attributePaths = {
                "operation",
                "operation.personInCharge",
                "operation.registrant",
                "subWorkType",
                "work"
            })
    Optional<SubWorkEntity> findByIdAndOperationDeletedAtIsNull(Long id);

    /*
     * 상위 업무 상세(OPS-003)의 하위 업무 목록. 목록에 필요한 것은 제목·담당자·상태·진행률
     * 뿐이라 유형·등록자는 끌어오지 않는다 (AP-14 — 목록에는 요약만).
     *
     * 정렬을 쿼리에 고정하는 이유는 시안에 정렬 기준이 없어 서버가 정해야 하기 때문이다.
     * 마감이 빠른 순으로 두고 마감 없는 건을 뒤로 보낸다 — NULL 정렬 기본값이 H2(먼저)와
     * PostgreSQL(나중)에서 갈리므로 nulls last를 명시해야 테스트와 운영이 같아진다.
     */
    @Query(
            "select s from SubWorkEntity s"
                    + " join fetch s.operation o"
                    + " join fetch o.personInCharge"
                    + " where s.work = :work and o.deletedAt is null"
                    + " order by s.dueAt asc nulls last, s.id asc")
    List<SubWorkEntity> findAllByWorkWithOwner(@Param("work") WorkEntity work);

    /*
     * 상위 업무 진행률 집계용. 소프트 삭제 여부는 부모 oper가 관리하므로(sub_work에는
     * del_dt가 없다) 조인해서 걸러낸다 — 삭제된 하위 업무는 분모에 들어가면 안 된다.
     */
    long countByWorkAndOperationDeletedAtIsNull(WorkEntity work);

    long countByWorkAndWorkStatusAndOperationDeletedAtIsNull(
            WorkEntity work, WorkStatus workStatus);
}
