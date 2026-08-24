package org.sscc.ssccopsserver.domain.academicprogram.service;

import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;

/*
 * 승인 후속 처리 (#133 · 학술관리_API설계.md §3.2 "(내부) 승인 후속 처리"). 공개 엔드포인트가
 * 아니다 — 사용자가 직접 부르는 액션이 아니라, 승인 이관(#150)이 Event + AcademicProgram +
 * CurriculumItem[]을 만든 직후 같은 트랜잭션에서 호출하는 서비스 메서드다(2026-08-24 재설계 —
 * 예전에는 APPROVE 액션의 부수 효과였던 것이 이제 생성 자체의 부수 효과다).
 *
 * #150이 아직 없어 이 이슈(#133)는 픽스처로 직접 AcademicProgramEntity를 만들어 이 서비스를
 * 검증한다(#131이 등록 API 없이 조회를 픽스처로 먼저 검증한 것과 같은 방식) — 학술관리_
 * 이슈목록.md S3 참고 자료.
 */
public interface AcademicProgramApprovalEffectsService {

    /*
     * 스터디장/팀장 역할 부여 + 문항 0개인 DRAFT 모집 폼 생성 + Event.form_id 연결을 한
     * 트랜잭션으로 묶는다. "생성은 됐는데 역할·모집 폼이 없는" 상태를 만들지 않는다 —
     * 역할 부여가 실패하면(예: ROLE_ALREADY_ASSIGNED) 폼도 만들어지지 않고 전체가 롤백된다.
     *
     * 호출부가 이미 저장한 academicProgram을 받는다. leader는 이미 채워져 있어야 한다
     * (AcademicProgramEntity.create()가 항상 proposer로 고정한다).
     */
    void applyPostApprovalEffects(AcademicProgramEntity academicProgram);
}
