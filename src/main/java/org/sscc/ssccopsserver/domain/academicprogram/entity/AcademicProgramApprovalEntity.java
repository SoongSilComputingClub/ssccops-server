package org.sscc.ssccopsserver.domain.academicprogram.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * academic_program_aprv(승인 이력) — 회차·종료 승인 기록 (#133, 학술관리_데이터모델.md §2).
 * `sub_work_aprv`와 같은 패턴(승인자·승인일시·사유)을 따르되, FK가 sub_work_id로 고정된
 * sub_work_aprv를 재사용하지 않고 테이블을 분리했다(질의응답으로 확정) — 승인 대상 종류마다
 * 커지는 변경을 학술 도메인 안에 가둔다.
 *
 * PROPOSAL은 aprv_pnt_cd에 없다 — 기획안 승인은 폼 응답 검토(#141)가 정본이다.
 *
 * session_id는 아직 엔티티 연관이 아니라 평범한 컬럼이다. Session 엔티티는 이 이슈(#133) 범위
 * 밖(#135·#136)이라 아직 없고, 이 이슈가 실제로 쓰는 것은 aprv_pnt_cd = COMPLETION(세션과
 * 무관, 항상 NULL)뿐이다. Session이 생기면 그때 @ManyToOne으로 승격한다.
 */
@Entity
@Table(name = "academic_program_aprv")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AcademicProgramApprovalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "academic_program_aprv_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academic_program_id", nullable = false, updatable = false)
    private AcademicProgramEntity academicProgram;

    /** 회차 승인일 때만 값이 있다(#136). COMPLETION은 항상 NULL이다 */
    @Column(name = "session_id")
    private Long sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "aprv_pnt_cd", nullable = false, length = 20)
    private AcademicProgramApprovalPoint point;

    @Enumerated(EnumType.STRING)
    @Column(name = "aprv_stts_cd", nullable = false, length = 20)
    private AcademicProgramApprovalStatus status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "aprvr_mbr_id", nullable = false, updatable = false)
    private MemberEntity approver;

    @Column(name = "opnn_cn", columnDefinition = "TEXT")
    private String opinionContent;

    /** 처리 일시. PENDING이면 NULL이다 */
    @Column(name = "aprv_dt")
    private Instant approvedAt;

    /*
     * 종료/수료 승인 기록(#133 APPROVE_COMPLETION). 대기 없이 곧바로 APPROVED로 확정되므로
     * 상태·처리 일시를 함께 받는다 — 회차 승인(#136)처럼 PENDING으로 먼저 만들고 나중에
     * 결정을 채우는 2단계가 아니다.
     */
    public static AcademicProgramApprovalEntity forCompletion(
            AcademicProgramEntity academicProgram, MemberEntity approver, Instant approvedAt) {
        return new AcademicProgramApprovalEntity(
                null,
                academicProgram,
                null,
                AcademicProgramApprovalPoint.COMPLETION,
                AcademicProgramApprovalStatus.APPROVED,
                approver,
                null,
                approvedAt);
    }
}
