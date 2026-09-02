package org.sscc.ssccopsserver.domain.academicprogram.service;

import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;

/*
 * 승인된 기획안 → 학술 활동 이관 (#150 · ssccops#148 Story의 서버 구현).
 *
 * 공개 엔드포인트가 아니다 — 사용자가 부르는 액션이 아니라, 폼 응답 검토(#141)가
 * sys_form_cd = 'PROPOSAL'인 응답을 ACCEPT할 때 **같은 트랜잭션 안에서** 실행되는 내부 로직이며
 * 그 호출은 SystemFormApprovalHook 구현체(ProposalApprovalHook)를 지난다.
 *
 * **승인이 곧 생성이다**(2026-08-23 설계 변경). AcademicProgram은 더 이상 PROPOSED로 태어나
 * 나중에 승인되지 않는다 — 기획안 접수·검토·반려·수정요청·재제출은 전부 폼 도메인이 맡고,
 * 이 서비스가 만드는 행은 처음부터 APPROVED다.
 */
public interface AcademicProgramMigrationService {

    /*
     * Event + AcademicProgram + CurriculumItem[]을 만들고, 곧바로 같은 트랜잭션에서 승인 후속
     * 처리(#133 · 리더 역할 부여 · 빈 모집 폼 생성)를 잇는다.
     *
     * **전부 아니면 전무다.** 파싱 실패·유형 매핑 실패·후속 처리 실패 중 무엇이든 예외로
     * 빠져나가면 호출부의 ACCEPT까지 함께 롤백된다 — "승인은 됐는데 활동이 없는" 상태를 만들지
     * 않는다(ssccops#148 BR).
     */
    AcademicProgramEntity migrate(FormResponseHistoryEntity response);
}
