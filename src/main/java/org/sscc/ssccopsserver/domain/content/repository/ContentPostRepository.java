package org.sscc.ssccopsserver.domain.content.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.sscc.ssccopsserver.domain.content.code.ContentCategory;
import org.sscc.ssccopsserver.domain.content.code.ContentPublishStatus;
import org.sscc.ssccopsserver.domain.content.entity.ContentPostEntity;

public interface ContentPostRepository extends JpaRepository<ContentPostEntity, Long> {

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, Long id);

    /** 익명 상세. 게시 상태를 질의 조건에 넣는다 — 초안과 없는 포스트가 같은 404다 */
    Optional<ContentPostEntity> findBySlugAndPublishStatus(
            String slug, ContentPublishStatus publishStatus);

    Optional<ContentPostEntity> findByIdAndPublishStatus(Long id, ContentPublishStatus status);

    /*
     * 익명 목록 — 게시본만, 분류 필터 선택, **활동일 역순 · 같은 날은 id 역순**. 커서는
     * (actv_ymd, post_id) 쌍이며 비교식이 정렬과 같은 두 컬럼으로 끝난다(ContentPostCursor).
     * 인덱스 idx_cntnt_post_pub_list가 이 모양 그대로다.
     */
    @Query(
            "select p from ContentPostEntity p"
                    + " where p.publishStatus = :status"
                    + " and (:category is null or p.category = :category)"
                    + " and (:cursorDate is null"
                    + "   or p.activityDate < :cursorDate"
                    + "   or (p.activityDate = :cursorDate and p.id < :cursorId))"
                    + " order by p.activityDate desc, p.id desc")
    List<ContentPostEntity> findAllForPublicList(
            @Param("status") ContentPublishStatus status,
            @Param("category") ContentCategory category,
            @Param("cursorDate") LocalDate cursorDate,
            @Param("cursorId") Long cursorId,
            Pageable limit);

    @Query(
            "select count(p) from ContentPostEntity p"
                    + " where p.publishStatus = :status"
                    + " and (:category is null or p.category = :category)")
    long countForPublicList(
            @Param("status") ContentPublishStatus status,
            @Param("category") ContentCategory category);

    /** 어드민 목록 — id 내림차순 커서. 상태·분류 필터는 선택 */
    @Query(
            "select p from ContentPostEntity p"
                    + " where (:status is null or p.publishStatus = :status)"
                    + " and (:category is null or p.category = :category)"
                    + " and (:cursorId is null or p.id < :cursorId)"
                    + " order by p.id desc")
    List<ContentPostEntity> findAllForAdminList(
            @Param("status") ContentPublishStatus status,
            @Param("category") ContentCategory category,
            @Param("cursorId") Long cursorId,
            Pageable limit);

    @Query(
            "select count(p) from ContentPostEntity p"
                    + " where (:status is null or p.publishStatus = :status)"
                    + " and (:category is null or p.category = :category)")
    long countForAdminList(
            @Param("status") ContentPublishStatus status,
            @Param("category") ContentCategory category);
}
