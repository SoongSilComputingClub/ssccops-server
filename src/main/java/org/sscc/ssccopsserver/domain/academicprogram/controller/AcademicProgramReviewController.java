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
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramApprovalCondition;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramApprovalResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramApprovalSearchResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionCrossCondition;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionCrossListResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionCrossSearchResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionReviewCondition;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionTransitionRequest;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionTransitionResponse;
import org.sscc.ssccopsserver.domain.academicprogram.service.AcademicProgramApprovalService;
import org.sscc.ssccopsserver.domain.academicprogram.service.SessionReviewService;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.authorization.RequireAuthority;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMember;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 회차 검토(#136 · 학술관리_API설계.md §1·§3.6)와 승인 이력 조회(#139 · §3.8) API. 회차
 * 승인·수정요청, 활동 경계를 넘어 회차를 훑는 두 목록, 그리고 활동 하나의 처리 이력이 여기
 * 모인다.
 *
 * **@RequireAuthority가 클래스 레벨이 아니라 핸들러마다 붙는다** (#139에서 옮겼다). 회차 검토
 * 셋은 종전 그대로 ACADEMIC_PROGRAM_MANAGE 전용이고 판정도 달라지지 않았지만, 승인 이력
 * 조회만은 "스터디장 본인 **또는** 학술국장"이라 애노테이션으로 표현되지 않는다 — 차이가
 * '좁다/넓다'가 아니라 OR이기 때문이다. 클래스에 관리권한이 남아 있으면 애스펙트가 먼저 돌아,
 * 자기 활동의 처리 이력을 보러 온 스터디장이 소유권 판정에 닿기도 전에 403을 받는다(메서드
 * 애노테이션은 클래스 것을 덮어쓸 뿐 해제하지 못한다). 그래서 세 핸들러에 같은 권한을 그대로
 * 옮겨 적고, 승인 이력의 OR은 서비스 레이어의
 * AcademicProgramOwnershipPolicy.requireLeaderOrManager가 판정한다 —
 * AcademicProgramRecruitmentController(#138)의 recruitment/applications와 같은 패턴이다.
 *
 * 그 결과 이 컨트롤러에도 '자격이 걸리지 않은 핸들러'가 없다 — 셋은 애노테이션이, 하나는
 * 정책이 끊는다. 핸들러를 추가할 때 애노테이션을 빠뜨리면 인증만으로 열리므로 주의할 것.
 *
 * 회차 기록 컨트롤러(AcademicProgramSessionController, #135)와 나누는 것은 인가의 근거가 다르기
 * 때문이다 — 그쪽은 활동 한정 소유권(leadrMbrId 본인)이라 AOP가 알 수 없고, 이쪽은 정적 권한
 * 코드 하나로 끝난다. 경로 접두사가 겹치는 자리가 둘 있지만 스프링이 리터럴 세그먼트를 경로
 * 변수보다 먼저 고르므로 충돌하지 않는다(폼 도메인의 PublicFormController ↔
 * FormResponseController와 같은 자리다):
 *   - GET /v1/academic-programs/sessions       (여기) ↔ GET /v1/academic-programs/{id} (활동 상세)
 *   - GET /v1/academic-programs/reviews/sessions (여기) ↔ GET .../{id}/sessions (활동별 회차 목록)
 *
 * 활동 상태 전이(START_RECRUITMENT·APPROVE_COMPLETION, #133)는 아직
 * AcademicProgramController에 있다. 설계 문서(§1)는 그것도 이 컨트롤러의 몫으로 적어 두었으나,
 * 옮기는 것은 이 이슈의 계약을 바꾸지 않는 순수 이동이라 별도로 다룬다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/academic-programs")
public class AcademicProgramReviewController {

    private final SessionReviewService sessionReviewService;
    private final AcademicProgramApprovalService academicProgramApprovalService;

    @Operation(
            summary = "회차 승인·수정요청",
            description =
                    "transition은 APPROVE 또는 REQUEST_REVISION이며 둘 다 SUBMITTED에서만 성립한다."
                            + " 그 밖의 상태(APPROVED·REVISION_REQUESTED)는 409"
                            + " INVALID_SESSION_TRANSITION이다 — 승인된 회차는 출석부·진행률의 기준선이라"
                            + " 되돌리지 않는다. REQUEST_REVISION은 reason이 필수이며(400"
                            + " REVISION_REASON_REQUIRED) 그 사유는 회차 상세의 latestOpinion으로 읽힌다."
                            + " 처리 결과는 academic_program_aprv에 한 건 남는다.")
    @PostMapping("/{academicProgramId}/sessions/{sessionId}/transitions")
    @RequireAuthority(AuthorityCode.ACADEMIC_PROGRAM_MANAGE)
    public ApiResponse<SessionTransitionResponse> transitionSession(
            @PathVariable Long academicProgramId,
            @PathVariable Long sessionId,
            @Valid @RequestBody SessionTransitionRequest request,
            @CurrentMember MemberEntity approver) {
        return ApiResponse.success(
                sessionReviewService.transitionSession(
                        academicProgramId, sessionId, request, approver));
    }

    @Operation(
            summary = "회차 이력 조회(활동 횡단)",
            description =
                    "여러 활동의 회차를 활동 경계 없이 필터링·검색한다. keyword는 활동명과 회차 주제를"
                            + " 함께 훑고, academicProgramId로 한 활동만 좁힐 수도 있다. 커서 페이징이며"
                            + " 기본 정렬은 진행일 내림차순이다 — 활동을 가로지르면 회차 번호 순서는 뜻을"
                            + " 잃는다. 활동 하나의 회차만 보는 화면은 이 API가 아니라 GET"
                            + " /v1/academic-programs/{id}/sessions다.")
    @GetMapping("/sessions")
    @RequireAuthority(AuthorityCode.ACADEMIC_PROGRAM_MANAGE)
    public ApiResponse<List<SessionCrossListResponse>> searchCrossSessions(
            @Valid @ModelAttribute SessionCrossCondition condition) {
        SessionCrossSearchResponse result = sessionReviewService.searchCrossSessions(condition);
        return ApiResponse.success(result.sessions(), result.page());
    }

    @Operation(
            summary = "회차·출석 승인 대기 목록",
            description =
                    "여러 활동의 SUBMITTED 회차만 모아 보여주는 검토 목록이다. 상태 필터를 받지 않는다"
                            + " — 검토를 기다리는 회차가 곧 SUBMITTED이며, 다른 상태까지 보려면 회차 이력"
                            + " 조회(GET /v1/academic-programs/sessions)를 쓴다. 기본 정렬은 진행일"
                            + " 오름차순이다(오래 기다린 건부터). page.overallCount는 대기 건수가 아니라"
                            + " 회차 전체 건수다.")
    @GetMapping("/reviews/sessions")
    @RequireAuthority(AuthorityCode.ACADEMIC_PROGRAM_MANAGE)
    public ApiResponse<List<SessionCrossListResponse>> searchPendingSessions(
            @Valid @ModelAttribute SessionReviewCondition condition) {
        SessionCrossSearchResponse result = sessionReviewService.searchPendingSessions(condition);
        return ApiResponse.success(result.sessions(), result.page());
    }

    /*
     * 승인 이력 조회 (#139). 활동 상세의 '처리 이력'과 스터디장 대시보드의 '내 제출 처리 현황'이
     * 이 배열을 공유한다.
     *
     * **@RequireAuthority가 없는 유일한 핸들러다.** 빠뜨린 것이 아니라 표현할 수 없는 것이며
     * (클래스 주석), 자격은 서비스가 활동을 찾은 뒤 requireLeaderOrManager로 끊는다.
     */
    @Operation(
            summary = "승인 이력 조회",
            description =
                    "회차·종료 두 지점의 처리 이력을 처리 최신순으로 내린다. aprvPntCd는 SESSION·"
                            + "COMPLETION만 받으며(기획안 승인 이력은 폼 응답 상세의 검토 이력에서 본다)"
                            + " 그 밖의 값은 400 INVALID_CODE_VALUE다. sessionId로 회차 하나의 이력만"
                            + " 좁힐 수 있고, 커서 페이징이다. 자격은 이 활동의 스터디장/팀장 본인 **또는**"
                            + " ACADEMIC_PROGRAM_MANAGE이고 어느 쪽도 아니면 403 FORBIDDEN이다 —"
                            + " 수정요청 사유(opnnCn)가 실리므로 팀원에게도 열지 않는다."
                            + " page.overallCount는 필터와 무관한 그 활동의 이력 전체 건수다.")
    @GetMapping("/{academicProgramId}/approvals")
    public ApiResponse<List<AcademicProgramApprovalResponse>> getApprovals(
            @PathVariable Long academicProgramId,
            @Valid @ModelAttribute AcademicProgramApprovalCondition condition,
            @CurrentMember MemberEntity requester) {
        AcademicProgramApprovalSearchResponse result =
                academicProgramApprovalService.getApprovals(
                        academicProgramId, condition, requester);
        return ApiResponse.success(result.approvals(), result.page());
    }
}
