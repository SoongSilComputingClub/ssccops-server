package org.sscc.ssccopsserver.domain.event.entity;

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
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.sscc.ssccopsserver.domain.event.code.EventParticipantStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * event_ptcp(행사_참가자) — 행사 참가(예정)자 명단 (ssccops#133 · D4·D5).
 *
 * 참가자는 회원만이다(D2·D4) — 신청 자체가 회원만 가능하므로 미가입 합격자는 존재하지 않고,
 * mbr_id가 NOT NULL인 것이 그 결정의 데이터 형태다. (event_id, mbr_id) UNIQUE가 중복 등록을
 * DB에서 막는다 — 선조회만으로는 동시 등록을 못 막는다.
 *
 * 명단은 심사 기록이 아니다. REJECTED는 폼 응답 심사에 남고 여기에는 확정·대기·(확정 후)
 * 취소만 올라간다. 행은 지우지 않고 상태 전이로만 관리한다 — 참가자 명단은 활동 이력으로
 * 영구 보존한다(D16). 학술관리의 팀원 명단(#138)도 이 테이블을 그대로 재사용한다.
 *
 * 등록·승격·취소 API와 그 검증(정원 경고·회원 상태 경고)은 셋업 범위 밖이다
 * (ssccops#146·#147).
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "event_ptcp",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_event_ptcp_event_member",
                        columnNames = {"event_id", "mbr_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class EventParticipantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_ptcp_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false, updatable = false)
    private EventEntity event;

    /** 참가 회원(mbr.mbr_id). 명단의 주인은 바뀌지 않으므로 updatable = false로 잠근다 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mbr_id", nullable = false, updatable = false)
    private MemberEntity member;

    @Enumerated(EnumType.STRING)
    @Column(name = "ptcp_stts_cd", nullable = false, length = 20)
    private EventParticipantStatus status;

    /*
     * 신청 근거가 된 폼 응답(form_rspns_hstry.form_rspns_id). 전화 접수 등 수동 등록이면
     * NULL이다 — 폼 없는 회원 대상 행사의 참가자가 정상이라 optional이다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "form_rspns_id", updatable = false)
    private FormResponseHistoryEntity formResponse;

    /** 등록 처리자(mbr.mbr_id) — EVENT_MANAGE 보유 운영자. 서버가 인증 주체로 기록한다 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rgtr_mbr_id", nullable = false, updatable = false)
    private MemberEntity registrant;

    @CreatedDate
    @Column(name = "crt_dt", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "mdfcn_dt", nullable = false)
    private Instant updatedAt;

    /*
     * 참가자 등록 팩토리. 상태(확정/대기)는 운영자의 명시적 선택이라 팩토리가 기본값을 정하지
     * 않는다 — 수락된 응답을 자동으로 확정하는 경로를 만들지 않는 것이 수동 심사(D5)의 전제다.
     */
    public static EventParticipantEntity register(
            EventEntity event,
            MemberEntity member,
            EventParticipantStatus status,
            FormResponseHistoryEntity formResponse,
            MemberEntity registrant) {
        return new EventParticipantEntity(
                null, event, member, status, formResponse, registrant, null, null);
    }
}
