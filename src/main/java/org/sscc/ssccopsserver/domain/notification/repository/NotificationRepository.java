package org.sscc.ssccopsserver.domain.notification.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.sscc.ssccopsserver.domain.notification.code.NotificationApp;
import org.sscc.ssccopsserver.domain.notification.code.NotificationType;
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

    /*
     * 앱 하나로 좁힌 같은 질의 (#535 · ADR-0047). 조건 두 갈래가 곧 기준표의 규칙 두 줄이다 —
     * 기준표가 이 앱으로 보내라고 적은 유형(`routedTypes`)이거나, 기준표에 행이 없어 «보낸 앱»을
     * 따르는 유형(`sendingAppTypes`)이면서 그 알림 행의 app_cd가 이 앱이거나.
     *
     * **판정을 SQL로 옮기지 않았다** — 두 집합은 NotificationRoutingPolicy가 캐시된 표에서
     * 계산해 넘긴다. noti를 noti_type_rcpn에 조인하면 «행이 없으면 보낸 앱»이 LEFT JOIN +
     * NULL 검사가 되어 조건이 읽히지 않고, 무엇보다 발송 쪽 판정과 조회 쪽 판정이 서로 다른
     * 코드가 된다(정책은 한 클래스여야 한다).
     *
     * 두 집합은 비어 있을 수 있다(그 앱에 오는 유형이 하나도 없는 설정). Hibernate가 빈 IN을
     * 거짓으로 렌더링하므로 «아무것도 안 보인다»가 그대로 옳은 답이다.
     */
    @Query(
            "select n from NotificationEntity n"
                    + " where n.member.id = :memberId"
                    + " and (:cursorId is null or n.id < :cursorId)"
                    + " and (n.type in :routedTypes"
                    + "      or (n.app = :app and n.type in :sendingAppTypes))"
                    + " order by n.id desc")
    List<NotificationEntity> findPageByMemberIdAndApp(
            @Param("memberId") Long memberId,
            @Param("cursorId") Long cursorId,
            @Param("app") NotificationApp app,
            @Param("routedTypes") Collection<NotificationType> routedTypes,
            @Param("sendingAppTypes") Collection<NotificationType> sendingAppTypes,
            Pageable limit);

    long countByMemberIdAndReadAtIsNull(Long memberId);

    /** 위 목록과 **같은 조건**의 안 읽은 수. 목록 응답의 unreadCount와 배지가 이것 하나를 쓴다 */
    @Query(
            "select count(n) from NotificationEntity n"
                    + " where n.member.id = :memberId"
                    + " and n.readAt is null"
                    + " and (n.type in :routedTypes"
                    + "      or (n.app = :app and n.type in :sendingAppTypes))")
    long countUnreadByMemberIdAndApp(
            @Param("memberId") Long memberId,
            @Param("app") NotificationApp app,
            @Param("routedTypes") Collection<NotificationType> routedTypes,
            @Param("sendingAppTypes") Collection<NotificationType> sendingAppTypes);

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
