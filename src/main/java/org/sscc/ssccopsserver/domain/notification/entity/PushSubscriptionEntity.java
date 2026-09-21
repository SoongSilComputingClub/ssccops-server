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
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.notification.code.NotificationApp;
import org.sscc.ssccopsserver.domain.notification.code.PushProvider;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * push_sbscrp(푸시_구독) — 회원이 등록한 브라우저 하나 (ssccops#446 · ADR-0045).
 *
 * 브라우저의 PushManager가 준 구독(endpoint · p256dh · auth)을 그대로 담는다. **endpoint가 곧
 * 구독의 정체성이다**(uk_push_sbscrp_endpt) — 같은 브라우저가 다시 등록하면 새 행이 아니라
 * 이 행을 갱신하며, 그때 로그인한 회원이 바뀌었으면 회원도 바뀐다(한 기기를 두 사람이 번갈아
 * 쓰는 경우 마지막으로 등록한 사람의 것이다).
 *
 * **endpoint는 로그에 싣지 않는다.** 푸시 서비스의 capability URL이라 아는 사람은 누구나 그
 * 브라우저에 메시지를 보낼 수 있다(암호화는 되지만 도착은 한다). 실패 로그는 이 행의 id로 남긴다.
 *
 * 회원 본인 데이터라 회원이 지워지면 함께 지워진다(V21 · V9 규칙). 발송 이력은 두지 않으며
 * 푸시 서비스가 404·410을 돌려주면 이 행을 지우는 것이 정리의 전부다(ADR-0045).
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "push_sbscrp",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_push_sbscrp_endpt",
                        columnNames = {"endpt"}),
        indexes = @Index(name = "idx_push_sbscrp_mbr", columnList = "mbr_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PushSubscriptionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "push_sbscrp_id")
    private Long id;

    // 회원 본인 데이터 — 회원이 지워지면 함께 지워진다 (V21 · V9 규칙 · ADR-0021)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "mbr_id", nullable = false)
    private MemberEntity member;

    @Enumerated(EnumType.STRING)
    @Column(name = "pvdr_cd", nullable = false, length = 20, updatable = false)
    private PushProvider provider;

    @Enumerated(EnumType.STRING)
    @Column(name = "app_cd", nullable = false, length = 20)
    private NotificationApp app;

    @Column(name = "endpt", nullable = false, length = 2000, updatable = false)
    private String endpoint;

    @Column(name = "p256dh_key", nullable = false, length = 255)
    private String p256dhKey;

    @Column(name = "auth_key", nullable = false, length = 255)
    private String authKey;

    @Column(name = "expry_dt")
    private Instant expiresAt;

    @CreatedDate
    @Column(name = "reg_dt", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "upd_dt", nullable = false)
    private Instant updatedAt;

    public static PushSubscriptionEntity subscribe(
            MemberEntity member,
            NotificationApp app,
            String endpoint,
            String p256dhKey,
            String authKey,
            Instant expiresAt) {
        return new PushSubscriptionEntity(
                null,
                member,
                PushProvider.WEB_PUSH,
                app,
                endpoint,
                p256dhKey,
                authKey,
                expiresAt,
                null,
                null);
    }

    /*
     * 같은 endpoint로 다시 등록됐다. 키가 바뀌었을 수 있고(브라우저가 구독을 갱신하면 endpoint는
     * 같아도 키가 바뀐다) 로그인한 회원이 바뀌었을 수 있다 — 전부 마지막 등록을 따른다.
     */
    public void renew(
            MemberEntity member,
            NotificationApp app,
            String p256dhKey,
            String authKey,
            Instant expiresAt) {
        this.member = member;
        this.app = app;
        this.p256dhKey = p256dhKey;
        this.authKey = authKey;
        this.expiresAt = expiresAt;
    }

    /** 이 회원의 구독인가 — DELETE가 남의 구독을 지우지 않게 하는 판정 */
    public boolean isOwnedBy(Long memberId) {
        return member.getId().equals(memberId);
    }
}
