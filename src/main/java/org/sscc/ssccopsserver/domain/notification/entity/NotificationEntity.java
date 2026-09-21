package org.sscc.ssccopsserver.domain.notification.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.notification.code.NotificationApp;
import org.sscc.ssccopsserver.domain.notification.code.NotificationTargetType;
import org.sscc.ssccopsserver.domain.notification.code.NotificationType;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * noti(알림) — 사람이 보는 알림 한 행 (ssccops#446 · ADR-0045).
 *
 * **이 행이 «알림이 만들어졌다»의 기록 전부다.** 푸시는 이 행을 만든 뒤 최선 노력으로 보내고
 * 결과는 로그(ELK)로만 남긴다 — 발송 이력 표를 두지 않기로 했다(ADR-0045). 그래서 푸시가
 * 실패해도 화면의 알림 목록에는 남고, 구독이 없는 회원도 목록으로는 본다.
 *
 * 제목·내용은 만들 때 굳힌다(대상 제목이 나중에 바뀌어도 알림은 그때의 문구다). 그래서 이
 * 행은 read_dt 말고는 바뀌지 않는다 — 수정 일시 열이 없는 이유다.
 *
 * **noti_key는 «같은 사건을 두 번 알리지 않는다»의 열쇠다**(uk_noti_mbr_key). 마감 스케줄러가
 * 재기동·중복 실행에도 D-1·첫 지연일 알림을 한 번만 보내야 하므로 키를
 * `{유형}:SUB_WORK:{id}:{마감일}`로 만들고 UNIQUE가 두 번째를 거절한다. 전이 알림은 같은 전이가
 * 두 번 일어날 수 있어(반려 뒤 재요청) 키가 NULL이다 — UNIQUE는 NULL을 서로 다른 값으로 본다.
 *
 * 대상은 (`trgt_type_cd`, `trgt_id`)이고 FK가 없다(`NotificationTargetType` 주석).
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "noti",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_noti_mbr_key",
                        columnNames = {"mbr_id", "noti_key"}),
        indexes = {
            @Index(name = "idx_noti_mbr_id_desc", columnList = "mbr_id, noti_id DESC"),
            @Index(name = "idx_noti_mbr_read", columnList = "mbr_id, read_dt")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class NotificationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "noti_id")
    private Long id;

    // 회원 본인 데이터 — 회원이 지워지면 함께 지워진다 (V21 · V9 규칙 · ADR-0021)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "mbr_id", nullable = false, updatable = false)
    private MemberEntity member;

    @Enumerated(EnumType.STRING)
    @Column(name = "noti_type_cd", nullable = false, length = 30, updatable = false)
    private NotificationType type;

    @Column(name = "ttl", nullable = false, length = 200, updatable = false)
    private String title;

    @Column(name = "cn", nullable = false, length = 500, updatable = false)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "app_cd", nullable = false, length = 20, updatable = false)
    private NotificationApp app;

    @Column(name = "lnk_path", nullable = false, length = 500, updatable = false)
    private String linkPath;

    @Enumerated(EnumType.STRING)
    @Column(name = "trgt_type_cd", nullable = false, length = 20, updatable = false)
    private NotificationTargetType targetType;

    @Column(name = "trgt_id", nullable = false, updatable = false)
    private Long targetId;

    @Column(name = "noti_key", length = 100, updatable = false)
    private String notificationKey;

    @Column(name = "read_dt")
    private Instant readAt;

    @CreatedDate
    @Column(name = "reg_dt", nullable = false, updatable = false)
    private Instant createdAt;

    /** 제목 200자 · 내용 500자 — 컬럼 길이를 넘는 문구는 잘라 넣는다(제목이 긴 하위 업무). */
    public static NotificationEntity create(
            MemberEntity recipient,
            NotificationType type,
            String title,
            String body,
            NotificationApp app,
            String linkPath,
            NotificationTargetType targetType,
            Long targetId,
            String notificationKey) {
        return new NotificationEntity(
                null,
                recipient,
                type,
                truncate(title, 200),
                truncate(body, 500),
                app,
                linkPath,
                targetType,
                targetId,
                notificationKey,
                null,
                null);
    }

    /** 이 회원의 알림인가 — 남의 알림은 없는 것과 같은 404다(NotificationErrorCode) */
    public boolean isOwnedBy(Long memberId) {
        return member.getId().equals(memberId);
    }

    public boolean isRead() {
        return readAt != null;
    }

    /*
     * 읽음 처리. **두 번 읽어도 거절하지 않는다** — 화면이 같은 알림을 두 번 눌렀을 때 두 번째가
     * 오류로 보일 이유가 없고, 처음 읽은 시각을 덮어쓰지 않는 것은 그 값이 이력이기 때문이다.
     */
    public void markRead(Instant now) {
        if (readAt == null) {
            this.readAt = now;
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
