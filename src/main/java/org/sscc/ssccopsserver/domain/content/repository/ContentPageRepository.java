package org.sscc.ssccopsserver.domain.content.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.sscc.ssccopsserver.domain.content.code.ContentPublishStatus;
import org.sscc.ssccopsserver.domain.content.entity.ContentPageEntity;

public interface ContentPageRepository extends JpaRepository<ContentPageEntity, Long> {

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, Long id);

    /*
     * 익명 조회 (ssccops#381). **조건에 게시 상태를 넣는다** — 조회 뒤 거르지 않는다
     * (PublicEventServiceImpl과 같은 태도). 초안과 없는 페이지가 같은 404가 되는 것이 이 질의
     * 모양에서 나온다.
     */
    Optional<ContentPageEntity> findBySlugAndPublishStatus(
            String slug, ContentPublishStatus publishStatus);

    /*
     * 어드민 목록 — id 내림차순 커서. 상태 필터는 선택이다. size + 1건을 읽어 hasNext를 판정하는
     * 것은 다른 목록과 같다(Pageable은 limit로만 쓴다 — offset 페이징은 AP-13이 금한다).
     */
    @Query(
            "select p from ContentPageEntity p"
                    + " where (:status is null or p.publishStatus = :status)"
                    + " and (:cursorId is null or p.id < :cursorId)"
                    + " order by p.id desc")
    List<ContentPageEntity> findAllForAdminList(
            @Param("status") ContentPublishStatus status,
            @Param("cursorId") Long cursorId,
            Pageable limit);

    @Query(
            "select count(p) from ContentPageEntity p"
                    + " where (:status is null or p.publishStatus = :status)")
    long countForAdminList(@Param("status") ContentPublishStatus status);
}
