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

    // Supabase JWT 최초 로그인 시 자동 프로비저닝되는 임시회원 등급 코드 (data.sql로 시드됨)
    public static final String TEMPORARY_CODE = "TEMP";

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
