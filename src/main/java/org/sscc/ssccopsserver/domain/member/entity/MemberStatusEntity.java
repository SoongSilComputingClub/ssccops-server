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

    // 임시회원 자동 프로비저닝 시 기본으로 부여하는 재학 상태 코드 (data.sql로 시드됨)
    public static final String ENROLLED_CODE = "ENROLLED";

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
