package org.sscc.ssccopsserver.domain.academicprogram.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramTypeEntity;

public interface AcademicProgramTypeRepository
        extends JpaRepository<AcademicProgramTypeEntity, String> {

    /*
     * 목록 조회(#130)는 indctSeqno 순으로 고정한다. 비활성 유형도 관리 목록에는 남기므로
     * 필터를 두지 않는다 — 기획안 작성 화면의 활성 유형만 노출하는 것은 웹의 몫이다
     * (학술관리_데이터모델.md §... 웹 이슈 W1).
     */
    List<AcademicProgramTypeEntity> findAllByOrderByDisplayOrderAsc();
}
