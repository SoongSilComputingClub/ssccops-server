package org.sscc.ssccopsserver.domain.operation.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * sub_work_rjct(하위 업무 반려) — 반려 전이(TR-04)가 일어날 때마다 한 건씩 쌓인다.
 * 반려 → 보완 → 재검토요청 → 재반려가 가능하므로 하위 업무당 여러 건이 될 수 있다.
 *
 * 반려 사유는 상태 이력(chg_rsn)에도 함께 남는다. 이력은 '전이의 사유'라는 공통 축이고
 * 이 테이블은 '반려 건'이라는 별도 리소스라, 승인함·통계가 반려만 추려 볼 수 있게 유지한다.
 *
 * 사유는 NOT NULL이다 — 반려는 사유 없이 성립하지 않는다 (VR-O06). 공백 문자열 차단은
 * DB가 못 하므로 전이 메서드가 REASON_REQUIRED(422)로 막는다.
 */
@Entity
@Table(name = "sub_work_rjct")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class SubWorkRejectionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sub_work_rjct_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sub_work_id", nullable = false, updatable = false)
    private SubWorkEntity subWork;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mbr_id", nullable = false, updatable = false)
    private MemberEntity rejector;

    // 이 반려가 어느 전이에서 나왔는지 (sub_work_aprv와 같은 이유로 데이터사전에 신설한 FK)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sub_work_stts_hstry_id", updatable = false)
    private SubWorkStatusHistoryEntity statusHistory;

    @Column(name = "rjct_rsn", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String reason;

    @Column(name = "rjct_dt", nullable = false, updatable = false)
    private Instant rejectedAt;

    public static SubWorkRejectionEntity record(
            SubWorkEntity subWork,
            MemberEntity rejector,
            SubWorkStatusHistoryEntity statusHistory,
            String reason,
            Instant rejectedAt) {
        return new SubWorkRejectionEntity(
                null, subWork, rejector, statusHistory, reason, rejectedAt);
    }
}
