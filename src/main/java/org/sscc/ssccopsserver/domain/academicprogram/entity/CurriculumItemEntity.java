package org.sscc.ssccopsserver.domain.academicprogram.entity;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * curriculum_item(회차별 계획) — 기획안 제출과 원자적으로 등록되고 이후 불변인 계획
 * (#131, 학술관리_데이터모델.md §1·§4). 실제 진행 여부·내용(session, 실적)은 별도 테이블이며
 * 이 이슈 범위 밖이다(#135) — 계획과 실적을 분리해야 "계획 대비 진행률"을 파생할 수 있다.
 *
 * 감사 컬럼(crt_dt·mdfcn_dt)을 두지 않는다 — 불변인 계획이라 수정 이력을 남길 자리가 없고,
 * ERD(§2)에도 없는 컬럼이다.
 */
@Entity
@Table(name = "curriculum_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class CurriculumItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "curriculum_item_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academic_program_id", nullable = false, updatable = false)
    private AcademicProgramEntity academicProgram;

    @Column(name = "seqno", nullable = false)
    private Integer seqno;

    @Column(name = "ttl", nullable = false, length = 200)
    private String title;

    @Column(name = "plan_dt")
    private LocalDate planDate;

    public static CurriculumItemEntity create(
            AcademicProgramEntity academicProgram,
            Integer seqno,
            String title,
            LocalDate planDate) {
        return new CurriculumItemEntity(null, academicProgram, seqno, title, planDate);
    }
}
