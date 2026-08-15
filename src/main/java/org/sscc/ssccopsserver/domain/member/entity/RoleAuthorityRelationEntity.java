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
import jakarta.persistence.UniqueConstraint;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * role_authrt_rel(역할_권한_관계) — 어떤 역할이 어떤 권한을 갖는지 (#9 · ssccops#69).
 *
 * 이 테이블이 있어서 코드가 역할을 가리키지 않아도 된다 (BR-M20). 새 역할(홍보국장 등)을
 * 화면에서 만들고 여기에 권한만 붙이면 배포 없이 동작한다 — 코드는 하려는 일(authrt_cd)만 안다.
 *
 * (role_id, authrt_cd) UNIQUE를 건다. 같은 권한을 같은 역할에 두 번 붙이면 권한 관리 화면에
 * 중복으로 뜨고 떼어낼 때 한 행만 지워져 하나가 남는다 (form_lbl_rel과 같은 이유).
 *
 * mdfcn_dt가 없는 것은 데이터사전 그대로다. 이 행에는 바꿀 값이 없고 붙였다 떼는 것만 있다.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "role_authrt_rel",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_role_authrt_rel_role_authority",
                        columnNames = {"role_id", "authrt_cd"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class RoleAuthorityRelationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "role_authrt_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false, updatable = false)
    private MemberRoleEntity role;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "authrt_cd", nullable = false, updatable = false)
    private AuthorityEntity authority;

    @CreatedDate
    @Column(name = "crt_dt", nullable = false, updatable = false)
    private Instant createdAt;

    public static RoleAuthorityRelationEntity create(
            MemberRoleEntity role, AuthorityEntity authority) {
        return new RoleAuthorityRelationEntity(null, role, authority, null);
    }
}
