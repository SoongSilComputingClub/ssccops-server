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
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "mbr_role_rel")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class MemberRoleAssignmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "mbr_role_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mbr_id", nullable = false, updatable = false)
    private MemberEntity member;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false, updatable = false)
    private MemberRoleEntity role;

    @Column(name = "role_bgng_ymd", nullable = false)
    private LocalDate roleStartDate;

    @Column(name = "role_end_ymd")
    private LocalDate roleEndDate;

    @Column(name = "rprs_role_yn", nullable = false)
    private Boolean representative;

    @CreatedDate
    @Column(name = "crt_dt", updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "mdfcn_dt")
    private Instant updatedAt;

    public static MemberRoleAssignmentEntity create(
            MemberEntity member,
            MemberRoleEntity role,
            LocalDate roleStartDate,
            Boolean representative) {
        return new MemberRoleAssignmentEntity(
                null, member, role, roleStartDate, null, representative, null, null);
    }

    public void updatePeriod(LocalDate roleStartDate, LocalDate roleEndDate) {
        this.roleStartDate = roleStartDate;
        this.roleEndDate = roleEndDate;
    }

    public void changeRepresentative(Boolean representative) {
        this.representative = representative;
    }

    public void end(LocalDate roleEndDate) {
        this.roleEndDate = roleEndDate;
    }
}
