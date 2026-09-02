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
import org.sscc.ssccopsserver.domain.event.code.error.EventErrorCode;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

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
     *
     * 등록으로 도달할 수 있는 상태인지는 여기서 끊는다(ssccops#146). 정원 초과는 **끊지
     * 않는다** — 정원은 참고치이고(D5) 초과 여부는 응답에 실려 화면이 경고한다.
     */
    public static EventParticipantEntity register(
            EventEntity event,
            MemberEntity member,
            EventParticipantStatus status,
            FormResponseHistoryEntity formResponse,
            MemberEntity registrant) {
        if (!status.isRegistrable()) {
            throw new GeneralException(EventErrorCode.INVALID_PARTICIPANT_REGISTRATION_STATUS);
        }
        return new EventParticipantEntity(
                null, event, member, status, formResponse, registrant, null, null);
    }

    /*
     * 참가 상태 전이 (ssccops#146 · D14 · PATCH .../participants/{eventPtcpId}).
     *
     * 전이표를 엔티티가 갖는 것은 FormEntity.changeStatus·EventEntity.changeStatus와 같은
     * 자리다 — 서비스에 if로 옮겨 적으면 상태를 바꾸는 경로가 늘 때마다 규칙이 복제된다.
     *
     *   WAITLISTED → CONFIRMED   승격 (결원이 나면 운영자가 수동으로 올린다)
     *   CONFIRMED  → WAITLISTED  강등 (#198 · 확정을 다시 대기로 내린다)
     *   CONFIRMED  → CANCELLED   취소 (확정된 사람이 못 오게 됐을 때, 운영자만)
     *
     * **강등은 #198에서 열었다.** 그전까지 확정은 사실상 되돌릴 수 없는 조작이라, 모집
     * 담당자가 잘못 확정한 신청자를 대기로 내리려면 취소(CANCELLED)로 보내는 수밖에 없었다 —
     * 그런데 취소에서 나가는 길이 없으므로 그 사람은 영영 대기자가 되지 못한다. 확정과 대기
     * 사이를 오가는 것은 결원·정원 조정이 반복되는 모집 운영의 정상 흐름이고, 그 왕복에
     * 방향이 하나만 있을 이유가 없다.
     *
     * 그 밖은 전부 400이다. 특히 **CANCELLED에서 나가는 길이 없다** — 취소를 되돌리는 것은
     * 새로 확정하는 것과 결과가 같은데, 그 사이의 정원 판단·대기 순서를 무시하고 되살리는
     * 경로가 되기 때문이다(강등을 연 뒤에도 그대로다. 강등은 아직 참가자인 사람의 자리를
     * 옮기는 것이고 취소 복원은 참가자가 아닌 사람을 되살리는 것이라 다른 일이다). 대기자를
     * 바로 취소로 보내는 길도 없다: 그 사람은 참가자였던 적이 없으므로 명단의 취소가 아니라
     * 신청 철회이며, 그것은 본인의 행위라 별도 이슈다(D14). 같은 상태로의 재지정도 400이다 —
     * 아무것도 바꾸지 않는 요청을 통과시키면 mdfcn_dt만 갱신돼 실제 승격·강등·취소 시점을 못
     * 찾는다(#78 NO_CHANGE와 같은 이유). **선발을 다시 저장하는 경로(#198)가 같은 값을 400으로
     * 받지 않는 것은 그 요청이 여기까지 오지 않기 때문이다** — 바뀔 것이 없으면 아예 부르지
     * 않는다(EventParticipationServiceImpl.registerOrUpdateParticipant).
     *
     * 정원은 여기서 보지 않는다. 승격이 정원을 넘겨도 막지 않는 것이 D5이고, 넘겼다는 사실은
     * 서비스가 응답에 실어 화면이 경고한다.
     */
    public void changeStatus(EventParticipantStatus nextStatus) {
        boolean allowed =
                (this.status == EventParticipantStatus.WAITLISTED
                                && nextStatus == EventParticipantStatus.CONFIRMED)
                        || (this.status == EventParticipantStatus.CONFIRMED
                                && (nextStatus == EventParticipantStatus.WAITLISTED
                                        || nextStatus == EventParticipantStatus.CANCELLED));
        if (!allowed) {
            throw new GeneralException(EventErrorCode.INVALID_PARTICIPANT_STATUS_TRANSITION);
        }
        this.status = nextStatus;
    }
}
