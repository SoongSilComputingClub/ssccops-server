package org.sscc.ssccopsserver.domain.academicprogram.service;

import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramCondition;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramDetailResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramSearchResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramTransitionRequest;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramTransitionResponse;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/*
 * 학술 활동(스터디/프로젝트) 조회(#131) + 국장 전용 상태 전이(#133). 등록은 폼 응답 승인 이관
 * (ssccops#148)이 맡으므로 여기에는 없다(2026-08-23 설계 변경) — AcademicProgram 행이 생기는
 * 자리가 이 서비스 밖으로 옮겨졌을 뿐, 조회 계약(isLeader/isProposer 판정 포함)은 그대로다.
 */
public interface AcademicProgramService {

    AcademicProgramDetailResponse getAcademicProgram(Long academicProgramId, MemberEntity viewer);

    AcademicProgramSearchResponse searchAcademicPrograms(
            AcademicProgramCondition condition, MemberEntity viewer);

    /*
     * 국장 전용 2액션(#133 · POST /v1/academic-programs/{id}/transitions). 전이표·사전 검증은
     * AcademicProgramTransition·AcademicProgramEntity.changeStatus가 갖고, 여기서는 START_
     * RECRUITMENT의 폼 오케스트레이션(기간 반영 → OPEN 전이)과 APPROVE_COMPLETION의 승인
     * 이력 기록만 더한다.
     */
    AcademicProgramTransitionResponse transition(
            Long academicProgramId,
            AcademicProgramTransitionRequest request,
            MemberEntity performer);
}
