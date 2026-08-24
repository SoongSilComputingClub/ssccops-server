package org.sscc.ssccopsserver.domain.academicprogram.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramApprovalEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramApprovalPoint;

/*
 * academic_program_aprv 저장소 (#133·#136·#139). 쓰기 경로는 APPROVE_COMPLETION(#133)과 회차
 * 승인·수정요청(#136) 둘이고, 읽기 경로는 회차 상세의 latestOpinion(#135)과 승인 이력 조회
 * (#139)다.
 */
public interface AcademicProgramApprovalRepository
        extends JpaRepository<AcademicProgramApprovalEntity, Long>,
                AcademicProgramApprovalRepositoryCustom {

    /*
     * 회차 상세(#135)의 latestOpinion — 그 회차에 마지막으로 달린 검토 의견(수정요청 사유).
     * 재제출은 이력을 남기지 않으므로(데이터모델 §7) 사유가 남는 곳은 이 테이블의 최신 행
     * 하나뿐이고, 화면은 "무엇을 고쳐야 하는가"를 여기서만 읽는다.
     *
     * 정렬을 처리 일시(aprv_dt)가 아니라 식별자로 하는 것은 그 컬럼이 PENDING이면 NULL이기
     * 때문이다 — 아직 처리되지 않은 최신 행이 정렬에서 뒤로 밀리면 "가장 최근"이 아니게 된다.
     * 승인 이력 목록(#139)의 정렬도 같은 이유로 식별자를 쓴다.
     *
     * SESSION 행을 만드는 것은 회차 승인(#136)이며, 아직 검토되지 않은 회차는 비어 있다.
     */
    Optional<AcademicProgramApprovalEntity> findFirstBySessionIdAndPointOrderByIdDesc(
            Long sessionId, AcademicProgramApprovalPoint point);

    /*
     * 활동 하나의 승인 이력 전체 건수(#139 · page.overallCount). 필터와 무관한 분모라 지점·회차
     * 필터가 걸려도 이 값은 변하지 않는다 — 화면이 '2건 · 전체 5건'으로 읽는다.
     */
    long countByAcademicProgramId(Long academicProgramId);
}
