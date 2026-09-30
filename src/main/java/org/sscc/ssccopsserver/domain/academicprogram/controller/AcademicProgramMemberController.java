package org.sscc.ssccopsserver.domain.academicprogram.controller;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramMemberAddRequest;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramMemberHistoryResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramMemberResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramMemberStatusChangeRequest;
import org.sscc.ssccopsserver.domain.academicprogram.service.AcademicProgramRecruitmentService;
import org.sscc.ssccopsserver.domain.event.code.EventParticipantStatus;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMember;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 팀원 명단 조회 API (#138 · 학술관리_API설계.md §3.7). event_ptcp를 학술관리 컨텍스트에서
 * 다시 내려주는 얇은 프록시이며 새 테이블은 없다(설계 결정 #3).
 *
 * 모집 컨트롤러(AcademicProgramRecruitmentController)와 나누는 것은 인가 레벨이 다르기
 * 때문이다 — 이쪽은 **인증만**이고(팀원 명단은 활동 상세 화면의 일부라 팀원 누구나 본다,
 * §6 매트릭스) 그쪽은 스터디장 소유권 또는 학술국장 권한이 걸린다. 한 컨트롤러에 두면 핸들러가
 * 하나 늘 때 자격을 빠뜨리는 것만으로 신청자 명부가 전원에게 열린다(회차 기록 #135와 회차
 * 검토 #136을 나눈 것과 같은 판단).
 *
 * 인증만이라는 것이 아무 값이나 내려도 된다는 뜻은 아니다 — 응답에서 개인정보를 덜어내는
 * 자리는 AcademicProgramMemberResponse다(행사 명단 DTO를 재사용하지 않는 이유가 그 주석에 있다).
 *
 * ── 팀원 관리(#612)도 여기 있다 ─────────────────────────────────────
 * 추가·상태 변경·이력은 «스터디장 본인 또는 학술국장»이다. 위에서 컨트롤러를 나눈 이유(한 곳에
 * 두면 자격을 빠뜨린 핸들러가 조용히 열린다)가 여기서는 서지 않는다 — 그 OR은 @RequireAuthority로
 * 표현되지 않아 **서비스가** 판정하고(AcademicProgramWritePolicy · OwnershipPolicy), 경로도 같은
 * /members 자원이다. 자격 판정이 빠진 쓰기는 AcademicProgramCompletionControllerTest.WritePath의
 * 403 테스트가 잡는다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/academic-programs/{academicProgramId}/members")
public class AcademicProgramMemberController {

    private final AcademicProgramRecruitmentService academicProgramRecruitmentService;

    @Operation(
            summary = "학술 활동 팀원 명단 조회",
            description =
                    "ptcpSttsCd를 생략하면 **취소(CANCELLED)를 포함한 전부**다 — 명단은 활동 이력으로"
                            + " 영구 보존하므로 취소된 행도 남는다(행사 참가자 명단과 같은 규칙)."
                            + " 정렬은 등록 순번(식별자 오름차순)이며, isLeader는 명단이 아니라"
                            + " acdm_actv.leadr_mbr_id와의 비교에서 온다."
                            + " 모집 시작 전이어도 409가 아니라 빈 목록이다 — 아직 아무도 뽑지 않은"
                            + " 활동의 빈 명단은 정상적인 답이다(신청자 조회·선발과 갈리는 지점).")
    @GetMapping
    public ApiResponse<List<AcademicProgramMemberResponse>> getMembers(
            @PathVariable Long academicProgramId,
            @RequestParam(required = false) EventParticipantStatus ptcpSttsCd,
            @CurrentMember MemberEntity viewer) {
        return ApiResponse.success(
                academicProgramRecruitmentService.getMembers(
                        academicProgramId, ptcpSttsCd, viewer));
    }

    /*
     * 팀원 추가 (#612). 201이 아니라 200인 것은 같은 요청이 새 행을 만들 수도(등록) 옛 행을 되살릴
     * 수도(재합류) 있어서다 — 결과는 어느 쪽이든 «이 회원이 지금 확정 팀원이다» 한 가지다.
     */
    @Operation(
            summary = "학술 활동 팀원 추가",
            description =
                    "스터디장 본인 또는 학술국장만 부른다. **신청서 없이** 확정(CONFIRMED)으로 넣는다 — 동아리 회원이면 누구나 되지만 탈퇴·제명"
                        + " 회원은 400 MEMBER_NOT_ADDABLE이다 (담당자 후보 GET /v1/members/assignable과 같은"
                        + " 기준). 예전에 제외된(취소) 회원이면 재합류다. 이미 확정·대기면 409 EVENT_PARTICIPANT_DUPLICATED."
                        + " 모집 시작 전이면 409 RECRUITMENT_NOT_STARTED, 종료·폐지된 활동이면 409"
                        + " ACADEMIC_PROGRAM_COMPLETED·ACADEMIC_PROGRAM_DISCONTINUED. 학술국장 승인 없이 바로"
                        + " 반영되고 변경 이력이 남는다.")
    @PostMapping
    public ApiResponse<AcademicProgramMemberResponse> addMember(
            @PathVariable Long academicProgramId,
            @Valid @RequestBody AcademicProgramMemberAddRequest request,
            @CurrentMember MemberEntity actor) {
        return ApiResponse.success(
                academicProgramRecruitmentService.addMember(academicProgramId, request, actor));
    }

    @Operation(
            summary = "학술 활동 팀원 상태 변경",
            description =
                    "스터디장 본인 또는 학술국장만 부른다. 승격(WAITLISTED→CONFIRMED) · 강등(CONFIRMED→WAITLISTED) ·"
                        + " 제외(CONFIRMED→CANCELLED) · 재합류(CANCELLED→CONFIRMED) 넷이며 그 밖은 400"
                        + " INVALID_PARTICIPANT_STATUS_TRANSITION이다. 제외해도 행은 지우지 않는다 — 지난 회차 출석은"
                        + " 그대로 남고 다음 회차 출석 대상(확정 팀원)에서만 빠진다. 이 활동의 명단 행이 아니면 404"
                        + " EVENT_PARTICIPANT_NOT_FOUND. 그 밖의 409는 추가와 같다.")
    @PatchMapping("/{eventPtcpId}")
    public ApiResponse<AcademicProgramMemberResponse> changeMemberStatus(
            @PathVariable Long academicProgramId,
            @PathVariable Long eventPtcpId,
            @Valid @RequestBody AcademicProgramMemberStatusChangeRequest request,
            @CurrentMember MemberEntity actor) {
        return ApiResponse.success(
                academicProgramRecruitmentService.changeMemberStatus(
                        academicProgramId, eventPtcpId, request, actor));
    }

    /*
     * /{eventPtcpId}가 PATCH만 받으므로 'history'가 명단 행 경로로 새지 않는다 — 그래도 GET 단건
     * 경로를 더할 날에는 리터럴 경로가 우선한다(Spring MVC의 패턴 우선순위).
     */
    @Operation(
            summary = "학술 활동 팀원 명단 변경 이력",
            description =
                    "스터디장 본인 또는 학술국장만 본다. 최신순이며 모집 선발(RECRUITMENT_SELECTION)·"
                            + "행사 참가자 API(EVENT_PARTICIPANTS)·팀원 관리(TEAM_MEMBERS) 세 경로가 남긴 줄이"
                            + " 전부 나온다. bfrPtcpSttsCd가 null이면 처음 명단에 오른 줄이다. 종료·폐지된"
                            + " 활동도 볼 수 있다.")
    @GetMapping("/history")
    public ApiResponse<List<AcademicProgramMemberHistoryResponse>> getMemberHistory(
            @PathVariable Long academicProgramId, @CurrentMember MemberEntity requester) {
        return ApiResponse.success(
                academicProgramRecruitmentService.getMemberHistory(academicProgramId, requester));
    }
}
