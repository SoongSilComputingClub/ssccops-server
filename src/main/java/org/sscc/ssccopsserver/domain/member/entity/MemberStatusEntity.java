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
@Table(name = "mbr_stts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class MemberStatusEntity {

    // 코드 문자열은 MemberStatusCode enum에 모아 두었다 (MemberGradeEntity와 같은 이유).

    @Id
    @Column(name = "mbr_stts_cd", length = 20)
    private String code;

    @Column(name = "mbr_stts_nm", nullable = false, length = 50)
    private String name;

    @Column(name = "indct_seqno", nullable = false)
    private Integer displayOrder;

    public static MemberStatusEntity create(String code, String name, Integer displayOrder) {
        return new MemberStatusEntity(code, name, displayOrder);
    }

    public void update(String name, Integer displayOrder) {
        this.name = name;
        this.displayOrder = displayOrder;
    }
}
