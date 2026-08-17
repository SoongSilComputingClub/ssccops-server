package org.sscc.ssccopsserver.domain.member.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import org.sscc.ssccopsserver.domain.member.code.RolePositionCode;

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

    /*
     * 이 역할이 어느 직위인가 (#118). 승인·투표 자격 판정이 role_nm 문자열 대신 보는 값이다.
     *
     * **UNIQUE를 걸지 않는다** — 홍보국장·행정국장·학술국장이 모두 DIRECTOR를 가져야 하므로
     * 식별자가 아니라 두 번째 축의 분류값이다(RolePositionCode 주석 참고).
     *
     * NULL이 기본이며 그 상태에서는 승인도 투표도 되지 않는다. 프로젝트장·스터디장이 그렇고,
     * 화면에서 만든 사용자 정의 역할도 지정하기 전까지 그렇다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "role_pstn_cd", length = 20)
    private RolePositionCode positionCode;

    @CreatedDate
    @Column(name = "crt_dt", updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "mdfcn_dt")
    private Instant updatedAt;

    public static MemberRoleEntity create(
            Integer displayOrder, String name, MemberRoleClassificationEntity roleClassification) {
        return create(displayOrder, name, roleClassification, null);
    }

    public static MemberRoleEntity create(
            Integer displayOrder,
            String name,
            MemberRoleClassificationEntity roleClassification,
            RolePositionCode positionCode) {
        return new MemberRoleEntity(
                null, displayOrder, name, roleClassification, positionCode, null, null);
    }

    public void update(
            Integer displayOrder,
            String name,
            MemberRoleClassificationEntity roleClassification,
            RolePositionCode positionCode) {
        this.displayOrder = displayOrder;
        this.name = name;
        this.roleClassification = roleClassification;
        this.positionCode = positionCode;
    }
}
