package org.sscc.ssccopsserver.domain.notification.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.sscc.ssccopsserver.domain.notification.entity.NotificationEntity;

/*
 * 알림 조회 (ssccops#446).
 *
 * 모든 질의가 mbr_id로 시작한다 — 알림은 자기 것만 다루는 자원이라 회원을 조건에서 빼는 메서드를
 * 두지 않는다(남의 알림을 읽는 경로가 생기지 않게).
 */
public interface NotificationRepository extends JpaRepository<NotificationEntity, Long> {

    /*
     * 내 알림을 최신부터 커서로(AP-13 · 커서는 id 하나 — 콘텐츠 어드민 목록과 같은 꼴).
     * 한 건 더 읽어 다음 페이지 유무를 안다(호출부가 size + 1을 넘긴다).
     */
    @Query(
            "select n from NotificationEntity n"
                    + " where n.member.id = :memberId"
                    + " and (:cursorId is null or n.id < :cursorId)"
                    + " order by n.id desc")
    List<NotificationEntity> findPageByMemberId(
            @Param("memberId") Long memberId, @Param("cursorId") Long cursorId, Pageable limit);

    long countByMemberIdAndReadAtIsNull(Long memberId);

    /*
     * 안 읽은 것 전부 읽음. 벌크 UPDATE인 것은 안 읽은 알림이 수백 건일 수 있어 행마다 dirty
     * check를 태울 이유가 없어서다. clearAutomatically로 영속성 컨텍스트의 옛 값을 버린다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            "update NotificationEntity n set n.readAt = :now"
                    + " where n.member.id = :memberId and n.readAt is null")
    int markAllRead(@Param("memberId") Long memberId, @Param("now") Instant now);

    boolean existsByMemberIdAndNotificationKey(Long memberId, String notificationKey);

    /** 읽은 지 90일이 지난 알림을 걷어내는 주간 정리(안 읽은 것은 남긴다 — ADR-0045) */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from NotificationEntity n where n.readAt is not null and n.readAt < :before")
    int deleteReadBefore(@Param("before") Instant before);
}
