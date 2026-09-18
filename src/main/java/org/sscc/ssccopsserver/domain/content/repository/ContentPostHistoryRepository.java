package org.sscc.ssccopsserver.domain.content.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.sscc.ssccopsserver.domain.content.entity.ContentPostHistoryEntity;

public interface ContentPostHistoryRepository
        extends JpaRepository<ContentPostHistoryEntity, Long> {

    @Query(
            "select h from ContentPostHistoryEntity h join fetch h.changer"
                    + " where h.post.id = :postId order by h.id desc")
    List<ContentPostHistoryEntity> findAllByPostId(@Param("postId") Long postId);
}
