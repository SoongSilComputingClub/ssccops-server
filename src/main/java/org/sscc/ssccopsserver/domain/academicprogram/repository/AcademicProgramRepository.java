package org.sscc.ssccopsserver.domain.academicprogram.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;

public interface AcademicProgramRepository
        extends JpaRepository<AcademicProgramEntity, Long>, AcademicProgramRepositoryCustom {

    /*
     * 단건 조회(#131)가 event·type·제출자·리더 이름까지 한 번에 내려야 하므로 연관을 한
     * 번에 끌어온다 — LAZY 그대로 두면 응답 조립 단계에서 연관마다 쿼리가 더 나간다(DB-13).
     */
    @Override
    @EntityGraph(attributePaths = {"event", "type", "proposer", "leader"})
    Optional<AcademicProgramEntity> findById(Long id);

    /*
     * 이미 이관된 기획안인가 (#150). UNIQUE(uk_acdm_actv_form_rspns)가 있는데도 선조회를
     * 두는 것은 위반을 사유 있는 409로 돌려주기 위해서다 — 제약만 두면 원인 모를 500이 되고,
     * 선조회만 두면 동시 요청이 그대로 통과한다(#20 회원가입이 세운 규칙 그대로).
     */
    boolean existsByFormResponse(FormResponseHistoryEntity formResponse);
}
