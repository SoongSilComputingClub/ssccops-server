package org.sscc.ssccopsserver.domain.notification.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.sscc.ssccopsserver.domain.notification.code.NotificationApp;
import org.sscc.ssccopsserver.domain.notification.code.NotificationType;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * noti_type_rcpn(알림_유형_수신) — «이 유형은 이 앱에 보인다» 한 쌍 (#535 · ADR-0047).
 *
 * 유형 하나가 여러 행을 가질 수 있고(여러 앱), **행이 하나도 없으면 미등록**이라 그 알림 행
 * 자신의 app_cd(= 보낸 앱) 하나가 답이다(NotificationRoutingPolicy). 즉 이 표의 부재가 곧
 * 기본 정책이며, 그래서 «빈 집합»(행은 있는데 앱이 없음)이라는 상태가 존재할 수 없다 —
 * 알림을 조용히 끄는 그 상태를 만들 수 없게 하는 것이 ADR-0047의 «최소 한 앱»이다.
 *
 * **noti_type_cd가 String이다.** `@Enumerated(STRING)`이 아닌 것은 둘 때문이다:
 * (1) enum에서 값을 뺀 뒤 남은 정책 행 하나가 이 표를 읽는 모든 조회를 터뜨린다 — 해석기가
 * 모르는 코드를 무시할 수 있어야 하고, 그러려면 여기까지는 문자열로 와야 한다.
 * (2) FlywayMigrationValidateTest.checkConstraintsMatchTheirEnums가 @Enumerated(STRING) 필드마다
 * 같은 집합의 CHECK 제약을 요구하는데, 이 컬럼에 CHECK를 걸지 않기로 했다(V23 주석 — 정책
 * 데이터라 유형이 늘 때마다 제약을 넓히는 마이그레이션을 강요할 값어치가 없다).
 * app_cd는 반대로 enum이고 CHECK가 있다.
 *
 * 값이 두 컬럼뿐이라 «수정»이 없다 — 수정 API는 그 유형의 행을 지우고 새로 넣는다. upd_dt는
 * 그래서 사실상 reg_dt와 같지만, 표를 직접 들여다볼 때 «언제 정해진 정책인가»를 남긴다.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "noti_type_rcpn",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_noti_type_rcpn",
                        columnNames = {"noti_type_cd", "app_cd"}),
        indexes = @Index(name = "idx_noti_type_rcpn_app", columnList = "app_cd"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class NotificationTypeRecipientEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "noti_type_rcpn_id")
    private Long id;

    /** NotificationType의 이름. enum이 아닌 이유는 클래스 주석 참고 */
    @Column(name = "noti_type_cd", nullable = false, length = 30, updatable = false)
    private String typeCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "app_cd", nullable = false, length = 20, updatable = false)
    private NotificationApp app;

    @CreatedDate
    @Column(name = "reg_dt", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "upd_dt", nullable = false)
    private Instant updatedAt;

    /** 유형과 앱을 잇는다. 유형은 enum으로 받는다 — 코드가 만드는 행에는 오타가 들어올 수 없다 */
    public static NotificationTypeRecipientEntity route(
            NotificationType type, NotificationApp app) {
        return new NotificationTypeRecipientEntity(null, type.name(), app, null, null);
    }
}
