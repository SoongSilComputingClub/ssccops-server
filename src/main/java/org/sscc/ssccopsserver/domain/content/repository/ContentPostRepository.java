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
     *
     * **첫 장과 커서 뒤를 질의 둘로 가른다** (#481). 처음엔 하나였다 —
     * `(:cursorDate is null or p.activityDate < :cursorDate or …)`. H2 테스트는 통과했는데
     * dev PostgreSQL 에서 두 번째 장이 500 이었다: `could not determine data type of parameter $4`.
     * 홀로 선 `? is null` 은 파라미터 타입을 정할 문맥이 없고, pgjdbc 가 LocalDate 를 타입 없이
     * 보내면 PostgreSQL 이 그 자리에서 거절한다. Long·enum 파라미터(`:category is null`)는
     * 드라이버가 타입을 실어 보내 지금은 돌지만 같은 모양이라 믿을 것이 못 된다 — 날짜·시각
     * 파라미터를 `is null` 로 검사하는 JPQL 은 쓰지 않는다. `cast(:x as date) is null` 로 피할 수도
     * 있지만 질의를 가르는 편이 읽기 쉽고 실행 계획도 단순하다.
     */
    @Query(
            "select p from ContentPostEntity p"
                    + " where p.publishStatus = :status"
                    + " and (:category is null or p.category = :category)"
                    + " order by p.activityDate desc, p.id desc")
    List<ContentPostEntity> findFirstPageForPublicList(
            @Param("status") ContentPublishStatus status,
            @Param("category") ContentCategory category,
            Pageable limit);

    @Query(
            "select p from ContentPostEntity p"
                    + " where p.publishStatus = :status"
                    + " and (:category is null or p.category = :category)"
                    + " and (p.activityDate < :cursorDate"
                    + "   or (p.activityDate = :cursorDate and p.id < :cursorId))"
                    + " order by p.activityDate desc, p.id desc")
    List<ContentPostEntity> findAfterCursorForPublicList(
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
