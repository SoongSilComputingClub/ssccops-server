package org.sscc.ssccopsserver.domain.academicprogram.service;

import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramCondition;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramDetailResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramSearchResponse;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/*
 * 학술 활동(스터디/프로젝트) 조회(#131). 등록은 폼 응답 승인 이관(ssccops#148)이 맡으므로
 * 여기에는 없다(2026-08-23 설계 변경) — AcademicProgram 행이 생기는 자리가 이 서비스 밖으로
 * 옮겨졌을 뿐, 조회 계약(isLeader/isProposer 판정 포함)은 그대로다.
 */
public interface AcademicProgramService {

    AcademicProgramDetailResponse getAcademicProgram(Long academicProgramId, MemberEntity viewer);

    AcademicProgramSearchResponse searchAcademicPrograms(
            AcademicProgramCondition condition, MemberEntity viewer);
}
