package org.sscc.ssccopsserver.domain.content.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.sscc.ssccopsserver.domain.content.entity.ContentPageHistoryEntity;

public interface ContentPageHistoryRepository
        extends JpaRepository<ContentPageHistoryEntity, Long> {

    /** 최신 개정이 먼저. 변경자를 함께 끌어와 이력 항목마다 회원을 다시 묻지 않는다 */
    @Query(
            "select h from ContentPageHistoryEntity h join fetch h.changer"
                    + " where h.page.id = :pageId order by h.id desc")
    List<ContentPageHistoryEntity> findAllByPageId(@Param("pageId") Long pageId);
}
