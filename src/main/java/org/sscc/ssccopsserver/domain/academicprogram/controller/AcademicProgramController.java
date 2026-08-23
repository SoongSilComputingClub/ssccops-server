package org.sscc.ssccopsserver.domain.academicprogram.controller;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramCondition;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramDetailResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramSearchResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramSummaryResponse;
import org.sscc.ssccopsserver.domain.academicprogram.service.AcademicProgramService;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMember;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 학술 활동(스터디/프로젝트) 조회 API (#131). 인증만 요구한다(일반 회원 누구나).
 *
 * 등록(POST)은 이 이슈 범위에서 빠졌다 — 2026-08-23 설계 변경(이슈 코멘트)으로 기획안 접수는
 * 폼 도메인(sys_form_cd='PROPOSAL')이 맡고, 폼 응답이 승인될 때 서버가
 * academic_program·event·curriculum_item을 만드는 이관으로 대체된다(ssccops#148, 커리큘럼
 * 구조화도 그 시점으로 옮긴다). AcademicProgram 엔티티 자체는 회차·출석·모집·팀원·진행률의
 * 앵커라 그대로 필요하며, 여기 남는 것은 그렇게 만들어진 행을 들여다보는 조회 두 개뿐이다.
 * `PROPOSED` 상태·`academic_program_aprv(PROPOSAL)`의 위치는 이관 설계(#148)와 함께 다시 본다.
 *
 * isLeader/isProposer 판정에 요청자 본인 식별이 필요해 조회에도 @CurrentMember를 쓴다
 * (설계 결정 #4) — 클라이언트가 leadrMbrId === 내 mbrId를 재계산하지 않는다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/academic-programs")
public class AcademicProgramController {

    private final AcademicProgramService academicProgramService;

    @Operation(summary = "활동 상세 조회", description = "기획안·활동 상세. 소프트 삭제가 없어 존재하면 항상 조회된다.")
    @GetMapping("/{academicProgramId}")
    public ApiResponse<AcademicProgramDetailResponse> getAcademicProgram(
            @PathVariable Long academicProgramId, @CurrentMember MemberEntity viewer) {
        return ApiResponse.success(
                academicProgramService.getAcademicProgram(academicProgramId, viewer));
    }

    @Operation(summary = "활동 목록 조회", description = "스터디·프로젝트 목록 화면, 대시보드 상태별 카운트 겸용. 커서 페이징이다.")
    @GetMapping
    public ApiResponse<List<AcademicProgramSummaryResponse>> searchAcademicPrograms(
            @Valid @ModelAttribute AcademicProgramCondition condition,
            @CurrentMember MemberEntity viewer) {
        AcademicProgramSearchResponse result =
                academicProgramService.searchAcademicPrograms(condition, viewer);
        return ApiResponse.success(result.academicPrograms(), result.page());
    }
}
