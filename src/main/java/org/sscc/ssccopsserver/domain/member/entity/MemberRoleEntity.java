package org.sscc.ssccopsserver.domain.member.entity;

import java.time.Instant;

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
@Table(name = "role")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class MemberRoleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "role_id")
    private Long id;

    @Column(name = "indct_seqno", nullable = false)
    private Integer displayOrder;

    @Column(name = "role_nm", length = 100)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_clsf_cd", nullable = false)
    private MemberRoleClassificationEntity roleClassification;

    @CreatedDate
    @Column(name = "crt_dt", updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "mdfcn_dt")
    private Instant updatedAt;

    /*
     * 직위 코드(role_pstn_cd, #118)는 승인·투표 자격이 권한 시스템으로 통합되며 삭제됐다(#123).
     * 자격은 이제 이 행이 아니라 역할↔권한 매핑(role_authrt_rel)에 실린다 — 역할 행에는
     * 판정에 쓰이는 값이 없다.
     */
    public static MemberRoleEntity create(
            Integer displayOrder, String name, MemberRoleClassificationEntity roleClassification) {
        return new MemberRoleEntity(null, displayOrder, name, roleClassification, null, null);
    }

    public void update(
            Integer displayOrder, String name, MemberRoleClassificationEntity roleClassification) {
        this.displayOrder = displayOrder;
        this.name = name;
        this.roleClassification = roleClassification;
    }
}
