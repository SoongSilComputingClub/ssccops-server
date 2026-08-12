package org.sscc.ssccopsserver.domain.member.entity;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "mbr_grd_hstry")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class MemberGradeHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "mbr_grd_hstry_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mbr_id", nullable = false, updatable = false)
    private MemberEntity member;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bfr_mbr_grd_cd", updatable = false)
    private MemberGradeEntity previousGrade;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "aftr_mbr_grd_cd", nullable = false, updatable = false)
    private MemberGradeEntity newGrade;

    @Column(name = "grd_aplcn_ymd", nullable = false, updatable = false)
    private LocalDate appliedDate;

    @Column(name = "grd_chg_rsn_cn", length = 500, updatable = false)
    private String changeReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chnrg_mbr_id", updatable = false)
    private MemberEntity changedBy;

    @CreatedDate
    @Column(name = "crt_dt", updatable = false)
    private Instant createdAt;

    public static MemberGradeHistoryEntity create(
            MemberEntity member,
            MemberGradeEntity previousGrade,
            MemberGradeEntity newGrade,
            LocalDate appliedDate,
            String changeReason,
            MemberEntity changedBy) {
        return new MemberGradeHistoryEntity(
                null, member, previousGrade, newGrade, appliedDate, changeReason, changedBy, null);
    }
}
