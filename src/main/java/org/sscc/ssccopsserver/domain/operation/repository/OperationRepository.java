package org.sscc.ssccopsserver.domain.operation.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.sscc.ssccopsserver.domain.operation.entity.OperationEntity;
import org.sscc.ssccopsserver.domain.operation.entity.OperationTagRelationEntity;

public interface OperationRepository extends JpaRepository<OperationEntity, Long> {

    // 소프트 삭제되지 않은 운영 건만 조회한다 (del_dt IS NULL)
    Optional<OperationEntity> findByIdAndDeletedAtIsNull(Long id);

    /*
     * 운영 건 묶음의 유형 상세 ID(work_id · sub_work_id · mtg_id)를 한 번에 읽는다 (#635 ·
     * ssccops#575). 회의 안건 응답이 «그 유형의 상세 ID»(targetId)를 싣는 데 쓴다 — 화면이
     * 운영 ID로 업무 상세를 열어 엉뚱한 업무가 열렸다.
     *
     * 세 확장 테이블을 left join 하는 쿼리 하나다 — 안건이 몇 건이든 1회다(DB-13). 기각: 유형별로
     * WorkRepository·SubWorkRepository·MeetingRepository를 따로 부르기 — 쿼리가 최대 3회가 되고
     * MeetingServiceImpl의 생성자가 넓어진다. 운영 건 하나는 확장 테이블 한 곳에만 행이 있으므로
     * 결과는 운영 건당 한 행이다. 삭제 여부는 보지 않는다 — 안건 응답이 운영 건의 제목을 삭제 여부와
     * 상관없이 싣는 것(AgendaTargetOperationResponse.from)과 맞춘다.
     */
    @Query(
            "select o.id as operationId, w.id as workId, s.id as subWorkId, m.id as meetingId"
                    + " from OperationEntity o"
                    + " left join WorkEntity w on w.operation = o"
                    + " left join SubWorkEntity s on s.operation = o"
                    + " left join MeetingEntity m on m.operation = o"
                    + " where o.id in :operationIds")
    List<OperationDetailIds> findDetailIdsByOperationIds(
            @Param("operationIds") Collection<Long> operationIds);

    /*
     * 업무·하위 업무·회의 목록·상세에 싣는 태그 칩 (#637). 이번 목록의 운영 건 전부를 한 번에 받아
     * 호출부가 운영 건별로 나눈다(OperationTagSummaryResponse.groupByOperationId) — 행마다 부르면
     * N+1이다 (DB-13). 정렬은 칩 순서(이름 오름차순)다.
     *
     * OperationTagRelationRepository가 아니라 여기 있는 것은 세 서비스(Work·SubWork·Meeting)가 이미
     * OperationRepository를 받고 있어서다 — 그 구현체들을 테스트 여러 곳이 new로 직접 만들므로
     * 생성자를 넓히면 그 호출이 전부 바뀐다.
     */
    @Query(
            "select r from OperationTagRelationEntity r join fetch r.tag t"
                    + " where r.operation.id in :operationIds order by t.name asc, t.id asc")
    List<OperationTagRelationEntity> findTagRelationsByOperationIds(
            @Param("operationIds") Collection<Long> operationIds);

    /*
     * 그 태그가 달린 운영 건 id (#637). 페이징이 없는 전량 목록(운영 통합 · 회의 목록)이 행을 거를 때
     * 쓴다 — 그 목록들은 어차피 전부 읽으므로 쿼리마다 exists를 더하는 대신 한 번 받아 메모리에서
     * 거른다. 페이징 목록(업무·하위 업무)은 커서·건수가 필터를 따라야 해 쿼리 안의 exists다.
     */
    @Query("select r.operation.id from OperationTagRelationEntity r where r.tag.id = :tagId")
    List<Long> findOperationIdsByTagId(@Param("tagId") Long tagId);
}
