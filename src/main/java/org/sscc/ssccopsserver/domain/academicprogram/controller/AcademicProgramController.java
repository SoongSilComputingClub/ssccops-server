package org.sscc.ssccopsserver.domain.academicprogram.controller;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramCondition;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramDetailResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramSearchResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramSummaryResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramTransitionRequest;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramTransitionResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.CurriculumItemWithSessionResponse;
import org.sscc.ssccopsserver.domain.academicprogram.service.AcademicProgramService;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.authorization.RequireAuthority;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMember;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 학술 활동(스터디/프로젝트) 조회(#131) + 커리큘럼 조회(#134) + 국장 전용 상태 전이(#133) API.
 * 조회 셋은 인증만 요구한다(일반 회원 누구나).
 *
 * 등록(POST)은 이 컨트롤러에 없다 — 2026-08-23 설계 변경(이슈 코멘트)으로 기획안 접수는
 * 폼 도메인(sys_form_cd='PROPOSAL')이 맡고, 폼 응답이 승인될 때 서버가
 * acdm_actv·event·crclm_artcl을 만드는 이관(#150)으로 대체된다. AcademicProgram
 * 엔티티 자체는 회차·출석·모집·팀원·진행률의 앵커라 그대로 필요하며, 여기 남는 것은 그렇게
 * 만들어진 행을 들여다보는 조회 셋(상세·목록·커리큘럼)과 이후 운영(모집 시작·종료 승인)을 미는
 * 전이 하나다.
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

    /*
     * 목록 조회 (#131). mine 필터가 역할을 함께 받는 이유는 AcademicProgramMineRole 주석에 있다
     * (#215) — mine=true는 스터디장과 제출자를 함께 주는데 isLeader는 리더만 참이라, 두 값의
     * 기준이 다르다는 것을 모르면 목록 길이로 "스터디장인가"를 판정하게 된다.
     */
    @Operation(
            summary = "활동 목록 조회",
            description =
                    "스터디·프로젝트 목록 화면, 대시보드 상태별 카운트 겸용. 커서 페이징이다."
                            + " mine은 역할 표기다 — mine=true는 **스터디장 또는 기획안 제출자**(지금까지의 동작),"
                            + " mine=leader는 스터디장/팀장 본인, mine=proposer는 제출자 본인의 활동만 본다."
                            + " mine 없음·빈 값·mine=false는 필터를 걸지 않고, 그 밖의 값은 400"
                            + " INVALID_CODE_VALUE다. **\"이 사람이 스터디장인가\"를 판정할 때는 mine=true가"
                            + " 아니라 mine=leader를 쓴다** — true는 제출만 한 회원에게도 결과를 주고, 그 행들은"
                            + " isLeader가 전부 false다.")
    @GetMapping
    public ApiResponse<List<AcademicProgramSummaryResponse>> searchAcademicPrograms(
            @Valid @ModelAttribute AcademicProgramCondition condition,
            @CurrentMember MemberEntity viewer) {
        AcademicProgramSearchResponse result =
                academicProgramService.searchAcademicPrograms(condition, viewer);
        return ApiResponse.success(result.academicPrograms(), result.page());
    }

    /*
     * 계획 조회 (#134). 활동 상세 화면의 "커리큘럼 대비 진행" 표 하나가 이 배열을 그대로 쓴다.
     * 페이징을 두지 않는 것은 활동당 회차 수가 적기 때문이다(학술관리_API설계.md §3.3).
     *
     * 개별 등록·수정·삭제 핸들러를 두지 않는다 — 커리큘럼은 승인 이관(#150) 시점에 한 번
     * 만들어지고 이후 불변이라 고칠 경로 자체가 없다(설계 결정 #2).
     */
    @Operation(
            summary = "커리큘럼(회차별 계획) 조회",
            description =
                    "계획에 실적(session)을 붙여 회차 순으로 내린다. 실적이 없는 회차도 sesnSttsCd에"
                            + " NOT_SUBMITTED가 채워지므로 클라이언트는 null 분기를 두지 않는다."
                            + " isEditable은 스터디장/팀장 본인이고 회차가 NOT_SUBMITTED·REVISION_REQUESTED일"
                            + " 때만 true다.")
    @GetMapping("/{academicProgramId}/curriculum-items")
    public ApiResponse<List<CurriculumItemWithSessionResponse>> getCurriculumItems(
            @PathVariable Long academicProgramId, @CurrentMember MemberEntity viewer) {
        return ApiResponse.success(
                academicProgramService.getCurriculumItems(academicProgramId, viewer));
    }

    /*
     * 상태 전이 (#133). 상세 화면의 '모집 시작'·'종료 승인' 버튼이 이 하나의 액션 경로를 쓴다
     * (work·form 도메인의 전이 엔드포인트 선례). START_RECRUITMENT/APPROVE_COMPLETION 둘 다
     * 학술국장 전용이라 클래스가 아니라 메서드에 건다 — 조회 두 개는 인증만 요구한다.
     *
     * 전이 가능 여부·폼 오케스트레이션·승인 이력 기록은 서비스와 도메인이 판단하므로 여기서
     * 분기하지 않는다. 상태 변경은 생성이 아니므로 200이다.
     */
    @Operation(
            summary = "학술 활동 상태 전이",
            description =
                    "transition은 START_RECRUITMENT 또는 APPROVE_COMPLETION이다."
                        + " APPROVED→ONGOING·ONGOING→COMPLETED만 허용하며 그 밖의 전이는 409"
                        + " INVALID_ACADEMIC_PROGRAM_TRANSITION으로 응답한다. START_RECRUITMENT는 연결된"
                        + " Form을 OPEN 전이한다 — 문항이 없으면 폼 도메인의 400 FORM_HAS_NO_QUESTION이 그대로 전파된다.")
    @RequireAuthority(AuthorityCode.ACADEMIC_PROGRAM_MANAGE)
    @PostMapping("/{academicProgramId}/transitions")
    public ApiResponse<AcademicProgramTransitionResponse> transition(
            @PathVariable Long academicProgramId,
            @Valid @RequestBody AcademicProgramTransitionRequest request,
            @CurrentMember MemberEntity performer) {
        return ApiResponse.success(
                academicProgramService.transition(academicProgramId, request, performer));
    }
}
