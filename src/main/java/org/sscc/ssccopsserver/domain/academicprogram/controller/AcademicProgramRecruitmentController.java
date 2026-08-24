package org.sscc.ssccopsserver.domain.academicprogram.controller;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramMemberResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.RecruitmentSelectRequest;
import org.sscc.ssccopsserver.domain.academicprogram.service.AcademicProgramRecruitmentService;
import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseSummaryResponse;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.authorization.RequireAuthority;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMember;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 모집 신청자 조회·선발 API (#138 · 학술관리_API설계.md §3.7). 기존 폼 응답 심사(#141)와 행사
 * 참가자 등록(ssccops#146)을 학술관리 컨텍스트에서 다시 여는 프록시이며 새 테이블은 없다.
 *
 * **@RequireAuthority가 클래스 레벨이 아니다.** 두 핸들러의 자격이 다르기 때문인데, 그 차이가
 * '좁다/넓다'가 아니라 AND/OR이라 애노테이션으로 표현되지 않는다 — 선발은
 * ACADEMIC_PROGRAM_MANAGE 단일 권한이지만(2026-08-23 확정, §7-1) 신청자 조회는 "스터디장 본인
 * **또는** 학술국장"이다. 클래스에 관리권한을 걸어 두면 애스펙트가 먼저 돌아, 자기 활동의
 * 신청자를 보러 온 스터디장이 소유권 판정에 닿기도 전에 403을 받는다(메서드 애노테이션은
 * 클래스 것을 덮어쓸 뿐 해제하지 못한다). 그래서 선발에만 메서드 레벨로 걸고, 조회의 OR은
 * 서비스 레이어의 AcademicProgramOwnershipPolicy.requireLeaderOrManager가 판정한다.
 *
 * 그 결과 이 컨트롤러에는 '자격이 걸리지 않은 핸들러'가 없다 — 하나는 애노테이션이, 하나는
 * 정책이 끊는다. 인증만으로 열리는 팀원 명단은 이 클래스가 아니라
 * AcademicProgramMemberController에 있다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/academic-programs/{academicProgramId}/recruitment")
public class AcademicProgramRecruitmentController {

    private final AcademicProgramRecruitmentService academicProgramRecruitmentService;

    /*
     * 신청자 목록. 폼 응답 목록(#37·#141)을 그대로 내려주므로 응답 스키마도 그쪽 DTO다.
     *
     * **page를 함께 내리지 않는다.** 설계 문서(§3.7)는 "+ page"로 적어 두었으나 폼 응답 목록에는
     * 페이징이 없고(#37 결정 — 목록을 한 번 받아 상태별로 걸러 보며 심사하는 화면이라 이전/다음
     * 이동이 페이지 경계에서 끊긴다), 여기에만 페이지를 씌우면 그 결정을 이 경로 하나가 뒤집는
     * 셈이 된다. 페이징이 필요해지면 폼 응답 목록과 함께 커서 기반으로 바꾼다.
     */
    @Operation(
            summary = "학술 활동 모집 신청자 목록 조회",
            description =
                    "연결된 모집 폼의 응답 목록을 그대로 내려준다(폼 응답 목록 API와 같은 규칙·같은 응답"
                            + " 스키마). statusCode를 생략하면 작성 중(DRAFT)을 뺀 전부이며 정렬은 제출"
                            + " 일시 내림차순이다. 자격은 이 활동의 스터디장/팀장 본인 **또는**"
                            + " ACADEMIC_PROGRAM_MANAGE이고 어느 쪽도 아니면 403 FORBIDDEN이다."
                            + " 모집 시작 전(APPROVED)이면 빈 목록이 아니라 409"
                            + " RECRUITMENT_NOT_STARTED다 — 빈 배열은 '아무도 지원하지 않았다'로 읽힌다."
                            + " 수락·거절 심사 자체는 이 경로가 아니라 폼 응답 검토 API"
                            + " (POST /v1/forms/{formId}/responses/{formRspnsId}/reviews)를 쓴다.")
    @GetMapping("/applications")
    public ApiResponse<List<FormResponseSummaryResponse>> getApplications(
            @PathVariable Long academicProgramId,
            @RequestParam(required = false) ResponseStatus statusCode,
            @CurrentMember MemberEntity requester) {
        return ApiResponse.success(
                academicProgramRecruitmentService.getApplications(
                        academicProgramId, statusCode, requester));
    }

    /*
     * 선발 확정. 새 자원(event_ptcp 행)이 생기지만 201이 아니라 200인 것은 응답이 만들어진
     * 자원이 아니라 **갱신된 팀원 명단 전체**이고(§3.7) 여러 줄을 한 번에 다루므로 Location으로
     * 가리킬 대상이 하나로 정해지지 않기 때문이다.
     */
    @Operation(
            summary = "학술 활동 모집 선발 확정",
            description =
                    "폼 응답 심사(ACCEPTED)와 팀원 등록(CONFIRMED/WAITLISTED)을 **한 트랜잭션**으로"
                            + " 함께 처리한다 — 한 줄이라도 실패하면 전부 되돌아간다. **학술국장 전용**"
                            + " (ACADEMIC_PROGRAM_MANAGE)이며 스터디장/팀장은 신청자 조회만 할 수 있다"
                            + " (2026-08-23 확정). ptcpSttsCd는 CONFIRMED·WAITLISTED만 받고 그 밖은"
                            + " 400 INVALID_PARTICIPANT_REGISTRATION_STATUS다. 이미 심사가 끝난"
                            + " 신청자를 다시 고르면 400 INVALID_RESPONSE_STATUS_TRANSITION, 이미"
                            + " 명단에 있는 회원이면 409 EVENT_PARTICIPANT_DUPLICATED다."
                            + " 모집 시작 전(APPROVED)이면 409 RECRUITMENT_NOT_STARTED."
                            + " **정원 초과는 차단하지 않는다**(참고치) — 화면이 활동 상세의"
                            + " cpctyMaxCnt와 응답 명단의 확정 인원을 비교해 경고한다.")
    @PostMapping("/select")
    @RequireAuthority(AuthorityCode.ACADEMIC_PROGRAM_MANAGE)
    public ApiResponse<List<AcademicProgramMemberResponse>> selectMembers(
            @PathVariable Long academicProgramId,
            @Valid @RequestBody RecruitmentSelectRequest request,
            @CurrentMember MemberEntity performer) {
        return ApiResponse.success(
                academicProgramRecruitmentService.selectMembers(
                        academicProgramId, request, performer));
    }
}
