package org.sscc.ssccopsserver.domain.academicprogram.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * acdm_actv_type(학술 활동 유형) — study/project 구분을 코드가 아니라 데이터로 두는
 * 기준 데이터 (#130).
 *
 * enum이 아니라 테이블인 것은 의도된 것이다. 세미나·특강·대회 등으로 유형이 늘어날 수 있다는
 * 방침(학술관리_기능범위.md §2)에 따라, 배포 없이 시드 추가만으로 확장 가능해야 한다.
 *
 * PK가 IDENTITY가 아니라 코드 문자열인 것은 acdm_actv.acdm_actv_type_cd가 이
 * 값을 직접 참조하기 때문이다(AuthorityEntity와 같은 이유). 코드는 등록 시 클라이언트가
 * 지정하고 이후 바뀌지 않는다.
 *
 * use_yn과 감사 컬럼은 sub_work_type과 같은 패턴이다. 유형은 (후속 이슈에서) AcademicProgram이
 * FK로 참조하므로 지우지 못하고 use_yn을 내린다 — 비활성 유형도 관리 목록에는 남긴다.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "acdm_actv_type")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AcademicProgramTypeEntity {

    @Id
    @Column(name = "acdm_actv_type_cd", length = 20)
    private String code;

    @Column(name = "type_nm", nullable = false, length = 50)
    private String name;

    @Column(name = "indct_seqno", nullable = false)
    private Integer displayOrder;

    @Column(name = "use_yn", nullable = false)
    private boolean active;

    @CreatedDate
    @Column(name = "crt_dt", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "mdfcn_dt", nullable = false)
    private Instant updatedAt;

    /** 유형 등록. 새 유형은 항상 활성이다 — 만들자마자 못 쓰게 할 이유가 없다. */
    public static AcademicProgramTypeEntity create(String code, String name, Integer displayOrder) {
        return new AcademicProgramTypeEntity(code, name, displayOrder, true, null, null);
    }

    /** 유형 수정. 코드는 PK라 바꾸지 않는다 — 이름·표시 순번만 갈아 끼운다. */
    public void update(String name, Integer displayOrder) {
        this.name = name;
        this.displayOrder = displayOrder;
    }

    /*
     * 사용 여부 전환. 후속 이슈에서 AcademicProgram이 이 유형을 FK로 참조하게 되면 유형은
     * 지우지 못한다 — 비활성 유형은 새 활동 등록의 선택지에서만 빠질 뿐, 이미 그 유형으로
     * 등록된 활동은 그대로 남는다(sub_work_type.use_yn과 같은 축).
     */
    public void changeActivation(boolean active) {
        this.active = active;
    }
}
