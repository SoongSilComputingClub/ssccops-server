package org.sscc.ssccopsserver.domain.member.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "mbr_grd")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class MemberGradeEntity {

    // 코드 문자열은 MemberGradeCode enum에 모아 두었다 — 엔티티마다 상수를 하나씩 두면
    // 코드가 늘어날수록 어디에 무엇이 있는지 알 수 없게 된다.

    @Id
    @Column(name = "mbr_grd_cd", length = 20)
    private String code;

    @Column(name = "mbr_grd_nm", nullable = false, length = 50)
    private String name;

    @Column(name = "indct_seqno", nullable = false)
    private Integer displayOrder;

    public static MemberGradeEntity create(String code, String name, Integer displayOrder) {
        return new MemberGradeEntity(code, name, displayOrder);
    }

    public void update(String name, Integer displayOrder) {
        this.name = name;
        this.displayOrder = displayOrder;
    }
}
