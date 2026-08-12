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
@Table(name = "mbr_stts_hstry")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class MemberStatusHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "mbr_stts_hstry_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mbr_id", nullable = false, updatable = false)
    private MemberEntity member;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bfr_mbr_stts_cd", updatable = false)
    private MemberStatusEntity previousStatus;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "aftr_mbr_stts_cd", nullable = false, updatable = false)
    private MemberStatusEntity newStatus;

    @Column(name = "stts_aplcn_ymd", nullable = false, updatable = false)
    private LocalDate appliedDate;

    @Column(name = "stts_end_prnmnt_ymd", updatable = false)
    private LocalDate expectedEndDate;

    @Column(name = "stts_chg_rsn_cn", length = 500, updatable = false)
    private String changeReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chnrg_mbr_id", updatable = false)
    private MemberEntity changedBy;

    @CreatedDate
    @Column(name = "crt_dt", updatable = false)
    private Instant createdAt;

    public static MemberStatusHistoryEntity create(
            MemberEntity member,
            MemberStatusEntity previousStatus,
            MemberStatusEntity newStatus,
            LocalDate appliedDate,
            LocalDate expectedEndDate,
            String changeReason,
            MemberEntity changedBy) {
        return new MemberStatusHistoryEntity(
                null,
                member,
                previousStatus,
                newStatus,
                appliedDate,
                expectedEndDate,
                changeReason,
                changedBy,
                null);
    }
}
