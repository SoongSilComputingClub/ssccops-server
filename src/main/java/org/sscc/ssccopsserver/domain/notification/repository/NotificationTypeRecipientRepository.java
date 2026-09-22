package org.sscc.ssccopsserver.domain.notification.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.sscc.ssccopsserver.domain.notification.entity.NotificationTypeRecipientEntity;

/*
 * 알림 수신 앱 기준표 (#535 · ADR-0047).
 *
 * **조회 메서드가 findAll() 하나다.** 36행이 상한인 표(유형 12 × 앱 3)를 통째로 읽어
 * NotificationRoutingPolicy가 캐시하므로 «이 유형의 앱»·«이 앱의 유형»을 묻는 질의를 두지
 * 않는다 — 두면 캐시를 우회하는 길이 생기고, 그 길로 발송 한 건이 조회 열두 번이 된다.
 */
public interface NotificationTypeRecipientRepository
        extends JpaRepository<NotificationTypeRecipientEntity, Long> {

    /*
     * 그 유형의 행을 전부 지운다 — 수정 API가 «지우고 다시 넣는다»로 도는 자리다.
     * 차집합을 계산해 더하고 빼는 안은 기각: 행이 최대 셋이라 얻는 것이 없고, 부분 실패에서
     * «절반만 바뀐 정책»이 남을 수 있다. 한 트랜잭션 안의 delete + insert가 더 단순하다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from NotificationTypeRecipientEntity r where r.typeCode = :typeCode")
    int deleteByTypeCode(@Param("typeCode") String typeCode);
}
