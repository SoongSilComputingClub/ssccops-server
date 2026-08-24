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
 * session은 #136에서 평범한 컬럼(Long)에서 @ManyToOne으로 승격했다 — #133 시점에는 Session
 * 엔티티 자체가 없어 식별자만 들고 있었다. COMPLETION은 활동 단위 승인이라 이 값이 항상
 * NULL이고(데이터모델 §2), SESSION일 때만 값이 있다.
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

    /*
     * 회차 승인일 때만 값이 있다(#136). COMPLETION은 활동 단위 승인이라 항상 NULL이다.
     *
     * updatable = false인 것은 이 행이 "그때 그 처리"를 가리키는 이력이기 때문이다 — 대상이
     * 바뀌는 승인 이력은 이력이 아니다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", updatable = false)
    private SessionEntity session;

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
     * 회차 승인·수정요청 기록(#136). 종료 승인과 마찬가지로 대기 없이 곧바로 결정을 담아
     * 만든다 — 제출 시점에 PENDING 행을 깔아 두지 않는 이유는 AcademicProgramApprovalStatus
     * 주석에 있다.
     *
     * 처리 상태를 전이 액션에서 꺼내 오는 것은 그 대응이 전이표의 일부이기 때문이다
     * (SessionTransition.approvalStatus) — 여기서 다시 분기하면 표가 두 벌이 된다.
     */
    public static AcademicProgramApprovalEntity forSession(
            AcademicProgramEntity academicProgram,
            SessionEntity session,
            SessionTransition transition,
            MemberEntity approver,
            String opinionContent,
            Instant approvedAt) {
        return new AcademicProgramApprovalEntity(
                null,
                academicProgram,
                session,
                AcademicProgramApprovalPoint.SESSION,
                transition.approvalStatus(),
                approver,
                opinionContent,
                approvedAt);
    }

    /*
     * 종료/수료 승인 기록(#133 APPROVE_COMPLETION). 대기 없이 곧바로 APPROVED로 확정되므로
     * 상태·처리 일시를 함께 받는다. 세션이 없는 유일한 지점이라 session은 NULL이다.
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
