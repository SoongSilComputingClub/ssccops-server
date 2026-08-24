package org.sscc.ssccopsserver.domain.academicprogram.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramApprovalEntity;

/*
 * academic_program_aprv 저장소 (#133). 지금 쓰는 쓰기 경로는 APPROVE_COMPLETION 하나뿐이라
 * save() 이상의 질의가 필요 없다 — 승인 이력 열람(GET .../approvals)은 후속 이슈(#134 계열)의
 * 몫이다.
 */
public interface AcademicProgramApprovalRepository
        extends JpaRepository<AcademicProgramApprovalEntity, Long> {}
