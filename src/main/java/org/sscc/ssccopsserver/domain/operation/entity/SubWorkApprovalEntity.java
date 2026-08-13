package org.sscc.ssccopsserver.domain.operation.entity;

import java.time.Instant;
import java.time.LocalDate;

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
 * sub_work_aprv(하위 업무 승인) — 승인·완료 전이(TR-03)가 일어날 때마다 한 건씩 쌓인다.
 * 반려 후 다시 검토요청해 재승인받는 흐름이 있으므로 하위 업무당 여러 건이 될 수 있다.
 *
 * rgtr_aprv_yn(등록자 승인 여부)은 자가 승인을 '차단'하는 값이 아니라 '표시'하는 값이다.
 * O-04가 확정되기 전까지의 잠정 정책이 허용 + 이력 식별 표시이기 때문이다 (POL-006).
 * 차단으로 확정되면 SELF_APPROVAL_BLOCKED(409)를 던지는 검사를 전이 메서드에 붙인다.
 *
 * 긴급 예외 집행(emrg_*·epfc_aprv_term_ymd — OPS-016)과 위험도 기반 승인 단계(aprv_stp)는
 * 아직 채우는 경로가 없어 매핑만 해 둔다. 정족수 투표(sub_work_aprv_vote — OPS-015)는
 * POL-007상 P2 예약 명세라 엔티티를 만들지 않았다.
 */
@Entity
@Table(name = "sub_work_aprv")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class SubWorkApprovalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sub_work_aprv_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sub_work_id", nullable = false, updatable = false)
    private SubWorkEntity subWork;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mbr_id", nullable = false, updatable = false)
    private MemberEntity approver;

    /*
     * 이 승인이 어느 전이에서 나왔는지. 승인이 여러 건 쌓이면 시각만으로는 이력과 짝지을 수
     * 없어 데이터사전에 신설한 FK다. 긴급 집행처럼 전이를 동반하지 않는 승인을 대비해 NULL을 허용한다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sub_work_stts_hstry_id", updatable = false)
    private SubWorkStatusHistoryEntity statusHistory;

    @Column(name = "sub_work_aprv_dt", nullable = false, updatable = false)
    private Instant approvedAt;

    // 등록자(oper.oper_rgtr_id)와 승인자가 같은지. 담당자가 아니라 등록자 기준이다
    @Column(name = "rgtr_aprv_yn", nullable = false, updatable = false)
    private boolean registrantApproval;

    @Column(name = "emrg_se_cd", length = 20)
    private String emergencyCode;

    @Column(name = "emrg_rsn", columnDefinition = "TEXT")
    private String emergencyReason;

    @Column(name = "epfc_aprv_term_ymd")
    private LocalDate postApprovalDueDate;

    @Column(name = "aprv_stp", length = 20)
    private String approvalStep;

    public static SubWorkApprovalEntity record(
            SubWorkEntity subWork,
            MemberEntity approver,
            SubWorkStatusHistoryEntity statusHistory,
            Instant approvedAt,
            boolean registrantApproval) {
        return new SubWorkApprovalEntity(
                null,
                subWork,
                approver,
                statusHistory,
                approvedAt,
                registrantApproval,
                null,
                null,
                null,
                null);
    }
}
