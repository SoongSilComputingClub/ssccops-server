package org.sscc.ssccopsserver.domain.academicprogram.controller;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.DeleteMapping;
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
import org.sscc.ssccopsserver.domain.share.code.ShareTargetType;
import org.sscc.ssccopsserver.domain.share.dto.ShareLinkResponse;
import org.sscc.ssccopsserver.domain.share.service.ShareLinkService;
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
    private final ShareLinkService shareLinkService;

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

    /*
     * 공유 링크 발급 (ssccops#311 · ADR-0016). 활동 상세 화면의 '공유' 버튼이 부른다.
     *
     * **이 셋이 `ShareController` 하나가 아니라 여기 있는 이유**는 `ShareLinkService` 주석에
     * 있다 — ssccops#306이 후보 ①(도메인 컨트롤러마다 얇게)로 확정했고 여기서는 그 형판을
     * 반복한다.
     *
     * **@RequireAuthority를 걸지 않는 것은 의도된 것이다.** 업무가 WORK_MANAGE가 아니라
     * WORK_READ를 요구한 근거는 *"볼 수 있는 사람이 공유할 수 있다"*였다(토큰이 주는 것은
     * 미리보기까지이고, 그것은 이 화면을 이미 보고 있는 사람이 아는 것을 넘지 않는다).
     * **학술의 대응 권한을 찾으면 그런 것이 없다** — 활동 상세·목록·커리큘럼 조회 셋은
     * 인증만 요구하고(클래스 주석), `ACADEMIC_PROGRAM_MANAGE`는 그와 짝이 아니라 국장 전용
     * 쓰기(전이·회차 승인)의 권한이다. 그러므로 같은 판단을 적용한 결과가 "인증만"이다.
     *
     * ACADEMIC_PROGRAM_MANAGE를 걸면 **정작 뿌릴 사람이 막힌다** — 스터디장/팀장은 정적 권한
     * 코드를 갖지 않고 `leadrMbrId` 소유권으로만 판정되는데(AuthorityCode 주석), 부원에게
     * 링크를 뿌리는 당사자가 바로 그 사람이다.
     *
     * **멱등이다.** 살아 있는 링크가 있으면 그것을 돌려주므로 몇 번을 눌러도 결과가 같다 —
     * 새 자원이 만들어지지 않는 호출이 있어 201이 아니라 200이다.
     */
    @Operation(
            summary = "학술 프로그램 공유 링크 발급",
            description =
                    "발급은 멱등이다 — 살아 있는 링크가 있으면 새로 만들지 않고 그것을 돌려주므로 응답은"
                            + " 언제나 200이다. 응답은 URL이 아니라 토큰(shrTkn)이며 웹이"
                            + " `{자기 origin}/s/{token}`을 조립한다. 없는 활동이면 404다.")
    @PostMapping("/{academicProgramId}/share")
    public ApiResponse<ShareLinkResponse> issueShareLink(
            @PathVariable Long academicProgramId, @CurrentMember MemberEntity issuer) {
        // 없는 활동을 여기서 404로 끊는다 — 조회를 먼저 태우지 않으면 존재하지 않는 대상에
        // 토큰이 발급된다(shr_lnk에 FK가 없어 DB가 막아 주지 않는다).
        academicProgramService.getAcademicProgram(academicProgramId, issuer);
        return ApiResponse.success(
                shareLinkService.issue(
                        ShareTargetType.ACADEMIC_PROGRAM, academicProgramId, issuer));
    }

    /*
     * 현재 공유 상태 (ssccops#311). 화면이 '공유하기'와 '공유 중지' 중 무엇을 그릴지 정한다.
     *
     * 공유한 적이 없거나 폐기했으면 **data가 null인 200**이다 — 404가 아닌 것은 '공유 중이
     * 아니다'가 오류가 아니라 정상적인 조회 결과이기 때문이다(업무·하위 업무와 같은 판단).
     */
    @Operation(
            summary = "학술 프로그램 공유 상태 조회",
            description = "공유 중이 아니면 404가 아니라 data가 null인 200이다. 없는 활동이면 404다.")
    @GetMapping("/{academicProgramId}/share")
    public ApiResponse<ShareLinkResponse> getShareLink(
            @PathVariable Long academicProgramId, @CurrentMember MemberEntity viewer) {
        academicProgramService.getAcademicProgram(academicProgramId, viewer);
        return ApiResponse.success(
                shareLinkService
                        .findActive(ShareTargetType.ACADEMIC_PROGRAM, academicProgramId)
                        .orElse(null));
    }

    /*
     * 공유 중지 (ssccops#311). 폐기하면 그 링크로는 미리보기도 상세도 열리지 않는다.
     *
     * 만료를 두지 않기로 했으므로(ADR-0016) **이것이 링크를 거두는 유일한 길이다.** 살아 있는
     * 링크가 없어도 조용히 지나가며 언제나 200이다.
     */
    @Operation(
            summary = "학술 프로그램 공유 중지",
            description =
                    "폐기하면 그 토큰으로는 미리보기가 404가 된다. 살아 있는 링크가 없어도 200이다 —"
                            + " 결과가 같은데 두 번째 요청만 오류로 만들 이유가 없다.")
    @DeleteMapping("/{academicProgramId}/share")
    public ApiResponse<Void> revokeShareLink(
            @PathVariable Long academicProgramId, @CurrentMember MemberEntity viewer) {
        academicProgramService.getAcademicProgram(academicProgramId, viewer);
        shareLinkService.revoke(ShareTargetType.ACADEMIC_PROGRAM, academicProgramId);
        return ApiResponse.successWithNoData();
    }
}
