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

    /*
     * 이 배정이 기준일에 유효한가 (BR-M25 — role_bgng_ymd <= 기준일 <= role_end_ymd, 종료일이
     * NULL이면 무기한).
     *
     * 같은 규칙의 질의 버전은 MemberRoleAssignmentRepository가 갖는다. 두 벌인 것처럼 보이지만
     * 쓰임이 다르다 — 저쪽은 '유효한 것만 골라 오기'이고 이쪽은 **이미 손에 든 행 하나**를
     * 판정하는 자리다. 종료된 배정까지 함께 실리는 목록(#81 current=false)은 행마다 배지를
     * 그려야 하므로 질의로는 답할 수 없다. 조건을 엔티티에 두는 것은 그 판정이 필요한 곳
     * (응답 DTO·대표 역할 단일성)마다 세 줄을 다시 적지 않기 위해서다.
     */
    public boolean isValidOn(LocalDate baseDate) {
        if (roleStartDate.isAfter(baseDate)) {
            return false;
        }
        return roleEndDate == null || !roleEndDate.isBefore(baseDate);
    }
}
