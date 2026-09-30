package org.sscc.ssccopsserver.domain.event.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.sscc.ssccopsserver.domain.event.code.EventParticipantChangePath;
import org.sscc.ssccopsserver.domain.event.code.EventParticipantStatus;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * event_ptcp_stts_hstry(행사_참가자_상태_이력) — 참가 상태가 바뀔 때마다 한 건씩 쌓이는 불변 이력
 * (#612 · ADR-0042 «자유도를 열면 이력을 붙인다»). 어휘는 sub_work_stts_hstry를 따른다.
 *
 * 스터디장이 팀원을 넣고 빼게 되면서(#612) 명단을 바꾸는 손이 셋이 됐다 — 그전까지 남는 것은
 * event_ptcp.mdfcn_dt 하나라 «언제 누가 어느 화면에서 뺐나»에 답할 수 없었다. **세 경로가 모두
 * 남긴다**(EventParticipationServiceImpl.recordStatusChange) — 한 경로만 적은 이력은 나머지 경로의
 * 변경을 «없었던 일»로 보이게 한다.
 *
 * - 등록(처음 명단에 오름)도 한 줄이다 — bfr_ptcp_stts_cd가 NULL이다. 알림이 등록을 «변경»으로
 *   세는 것(EventParticipantStatusChangedEvent)과 같은 판단이다.
 * - 수행자(prfmr_id)는 행위자 참조라 cascade가 없다 — 회원 하드 삭제를 막는 표
 *   (MemberReferenceConstraints)에 이 FK가 있다. 반대로 참가 행(event_ptcp_id)은 cascade다 — 참가 행이
 *   회원 삭제로 지워지면(V9) 그 행의 이력도 뜻을 잃는다(atndc와 같다).
 * - chg_dt는 DB 기본값이 아니라 애플리케이션이 Clock으로 채운다(SubWorkStatusHistoryEntity와 같은
 *   이유 — 테스트가 시각을 고정할 수 있어야 한다).
 *
 * 변경 메서드를 열지 않는다 — 전 컬럼 updatable = false. 이력은 고치지도 지우지도 않는다.
 */
@Entity
@Table(
        name = "event_ptcp_stts_hstry",
        indexes =
                @Index(
                        name = "idx_event_ptcp_stts_hstry_event_ptcp_id",
                        columnList = "event_ptcp_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class EventParticipantStatusHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_ptcp_stts_hstry_id")
    private Long id;

    /*
     * 제약 이름을 V28과 같게 박는다 — H2(테스트 · ddl-auto: create)가 Hibernate 해시 이름을 만들면
     * FlywayMigrationValidateTest가 PostgreSQL에서 이름으로 보는 cascade 목록과 갈린다.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(
            name = "event_ptcp_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_event_ptcp_stts_hstry_event_ptcp"))
    private EventParticipantEntity participant;

    /** 이전 상태. 처음 명단에 오른 등록이면 NULL이다 */
    @Enumerated(EnumType.STRING)
    @Column(name = "bfr_ptcp_stts_cd", length = 20, updatable = false)
    private EventParticipantStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "aftr_ptcp_stts_cd", nullable = false, length = 20, updatable = false)
    private EventParticipantStatus nextStatus;

    /*
     * 바꾼 회원. 조회·매핑 전용 연관이다. 제약 이름은 회원 하드 삭제의 409 번역표
     * (MemberReferenceConstraints)가 키로 쓴다 — H2에서도 같은 이름이어야 번역이 맞는다.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "prfmr_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_event_ptcp_stts_hstry_prfmr"))
    private MemberEntity performer;

    @Enumerated(EnumType.STRING)
    @Column(name = "chg_path_se_cd", nullable = false, length = 20, updatable = false)
    private EventParticipantChangePath changePath;

    @Column(name = "chg_dt", nullable = false, updatable = false)
    private Instant changedAt;

    public static EventParticipantStatusHistoryEntity record(
            EventParticipantEntity participant,
            EventParticipantStatus previousStatus,
            EventParticipantStatus nextStatus,
            MemberEntity performer,
            EventParticipantChangePath changePath,
            Instant changedAt) {
        return new EventParticipantStatusHistoryEntity(
                null, participant, previousStatus, nextStatus, performer, changePath, changedAt);
    }
}
