package org.sscc.ssccopsserver.domain.operation.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.WorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.WorkStatus;

public interface SubWorkRepository
        extends JpaRepository<SubWorkEntity, Long>, SubWorkRepositoryCustom {

    /*
     * 상세 조회(OPS-009)용 단건 조회. 소프트 삭제 여부는 부모 oper가 관리하므로 조인해서
     * 걸러내고, 삭제된 건은 없는 것으로 본다(404).
     *
     * 상세 응답이 담당자·등록자 이름과 유형명·상위 업무 식별자를 모두 쓰므로 연관을 한 번에
     * 끌어온다 — LAZY 그대로 두면 응답 조립 단계에서 연관마다 쿼리가 더 나간다 (DB-13).
     *
     * work.operation까지 실는 것은 상위 업무의 **이름**이 work가 아니라 그 상위 oper에 있기
     * 때문이다 (#70의 workTitle). EntityGraph에 넣은 연관은 같은 SELECT의 조인이 되므로
     * 조회 횟수는 그대로다 — 테스트가 3회·4회로 못 박아 둔다.
     */
    @EntityGraph(
            attributePaths = {
                "operation",
                "operation.personInCharge",
                "operation.registrant",
                "subWorkType",
                "work",
                "work.operation"
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

    /*
     * 목록 조회(OPS-008)가 화면 우상단에 표시하는 '전체 N건'. 필터를 걸지 않았을 때의 건수라
     * 조건이 없고, 삭제된 건을 빼는 기준만 목록과 같아야 한다 (AGG-03).
     */
    long countByOperationDeletedAtIsNull();

    /*
     * 상위 업무 목록(OPS-020)의 진행률·하위 업무 건수 집계. 업무마다 하위 업무를 조회하면
     * 그대로 N+1이므로 이번 페이지의 업무 전체를 한 번에 읽는다 (DB-13).
     *
     * 엔티티가 아니라 프로젝션인 것은 집계에 필요한 값이 셋뿐이기 때문이다 — 상세 화면과 달리
     * 카드는 하위 업무의 제목도 담당자도 그리지 않는다 (AP-14).
     *
     * 소프트 삭제 여부는 부모 oper가 관리하므로(sub_work에는 del_dt가 없다) 조인해서 걸러낸다.
     * 빈 컬렉션을 넘기면 IN () 이 되어 DB에 따라 문법 오류가 나므로 호출 전에 걸러야 한다.
     */
    @Query(
            "select w.id as workId, s.id as subWorkId, s.workStatus as workStatus"
                    + " from SubWorkEntity s"
                    + " join s.work w"
                    + " join s.operation o"
                    + " where w.id in :workIds and o.deletedAt is null")
    List<WorkSubWorkAggregate> findAggregatesByWorkIds(@Param("workIds") Collection<Long> workIds);
}
