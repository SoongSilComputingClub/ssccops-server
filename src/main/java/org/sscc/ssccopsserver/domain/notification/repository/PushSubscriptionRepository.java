package org.sscc.ssccopsserver.domain.notification.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.sscc.ssccopsserver.domain.notification.entity.PushSubscriptionEntity;

/*
 * 푸시 구독 조회 (ssccops#446).
 */
public interface PushSubscriptionRepository extends JpaRepository<PushSubscriptionEntity, Long> {

    /** endpoint가 구독의 정체성이다 — 등록(upsert)과 해지가 이것으로 찾는다 */
    Optional<PushSubscriptionEntity> findByEndpoint(String endpoint);

    /*
     * 수신자들의 구독 전부. 발송기가 회원마다 묻지 않고 한 번에 읽는다 — 승인 요청은 결재 권한
     * 보유자 전원에게 가므로 수신자가 여럿이다. 빈 컬렉션은 호출부가 거른다(IN ()은 문법 오류).
     */
    @Query(
            "select s from PushSubscriptionEntity s"
                    + " join fetch s.member m"
                    + " where m.id in :memberIds")
    List<PushSubscriptionEntity> findAllByMemberIds(@Param("memberIds") Collection<Long> memberIds);
}
