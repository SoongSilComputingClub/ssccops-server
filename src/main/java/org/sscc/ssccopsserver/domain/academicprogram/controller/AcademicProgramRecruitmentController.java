package org.sscc.ssccopsserver.domain.academicprogram.controller;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramMemberResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.RecruitmentApplicationResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.RecruitmentFormQuestionUpdateRequest;
import org.sscc.ssccopsserver.domain.academicprogram.dto.RecruitmentFormResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.RecruitmentScheduleResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.RecruitmentScheduleUpdateRequest;
import org.sscc.ssccopsserver.domain.academicprogram.dto.RecruitmentSelectRequest;
import org.sscc.ssccopsserver.domain.academicprogram.service.AcademicProgramRecruitmentService;
import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
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
 *
 * **모집 폼 2종(#483)도 그 정책 쪽이다.** 스터디장/프로젝트장 역할에는 권한이 하나도 없어
 * (V3 시드 — "국원·프로젝트장·스터디장은 부여하지 않는다") FORM_READ·FORM_WRITE로 잠긴 폼
 * 경로에 닿지 못했고, 그래서 지원서 문항을 실제로 채우는 사람이 리더가 아니라 학술국장이었다.
 * 정적 권한 코드를 새로 만들지 않은 것은 "이 활동 한정 관계"가 역할로 표현되지 않기 때문이며
 * (학술관리_API설계.md §4), 그 판단은 신청자 조회가 이미 한 것과 같다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/academic-programs/{academicProgramId}/recruitment")
public class AcademicProgramRecruitmentController {

    private final AcademicProgramRecruitmentService academicProgramRecruitmentService;

    /*
     * 신청자 목록. 폼 응답 목록(#37·#141)에 **참가 상태**를 얹어 내려준다(#198) — 선발이 심사와
     * 등록을 함께 하므로 확정이든 대기든 응답은 똑같이 ACCEPTED가 되고, 폼 응답 요약 DTO만으로는
     * 그 둘이 구별되지 않는다. 그 필드를 폼 도메인의 DTO에 더하지 않은 이유는
     * RecruitmentApplicationResponse 주석에 있다.
     *
     * **page를 함께 내리지 않는다.** 설계 문서(§3.7)는 "+ page"로 적어 두었으나 폼 응답 목록에는
     * 페이징이 없고(#37 결정 — 목록을 한 번 받아 상태별로 걸러 보며 심사하는 화면이라 이전/다음
     * 이동이 페이지 경계에서 끊긴다), 여기에만 페이지를 씌우면 그 결정을 이 경로 하나가 뒤집는
     * 셈이 된다. 페이징이 필요해지면 폼 응답 목록과 함께 커서 기반으로 바꾼다.
     */
    @Operation(
            summary = "학술 활동 모집 신청자 목록 조회",
            description =
                    "연결된 모집 폼의 응답 목록에 참가 상태(eventPtcpId·ptcpSttsCd)를 얹어 내려준다"
                            + " (그 밖의 필드·기본값·정렬은 폼 응답 목록 API와 같다)."
                            + " **아직 선발되지 않은 신청자는 두 값이 모두 null**이며 서버가 '미선발'"
                            + " 같은 대체값을 만들지 않는다. 참가 상태는 응답이 아니라 회원 기준이라"
                            + " 같은 회원의 응답이 여러 줄이면 같은 값이 함께 실린다."
                            + " statusCode를 생략하면 작성 중(DRAFT)을 뺀 전부이며 정렬은 제출"
                            + " 일시 내림차순이다. 자격은 이 활동의 스터디장/팀장 본인 **또는**"
                            + " ACADEMIC_PROGRAM_MANAGE이고 어느 쪽도 아니면 403 FORBIDDEN이다."
                            + " 모집 시작 전(APPROVED)이면 빈 목록이 아니라 409"
                            + " RECRUITMENT_NOT_STARTED다 — 빈 배열은 '아무도 지원하지 않았다'로 읽힌다."
                            + " 수락·거절 심사 자체는 이 경로가 아니라 폼 응답 검토 API"
                            + " (POST /v1/forms/{formId}/responses/{formRspnsId}/reviews)를 쓴다.")
    @GetMapping("/applications")
    public ApiResponse<List<RecruitmentApplicationResponse>> getApplications(
            @PathVariable Long academicProgramId,
            @RequestParam(required = false) ResponseStatus statusCode,
            @CurrentMember MemberEntity requester) {
        return ApiResponse.success(
                academicProgramRecruitmentService.getApplications(
                        academicProgramId, statusCode, requester));
    }

    /*
     * 선발 저장. 새 자원(event_ptcp 행)이 생기지만 201이 아니라 200인 것은 응답이 만들어진
     * 자원이 아니라 **갱신된 팀원 명단 전체**이고(§3.7) 여러 줄을 한 번에 다루므로 Location으로
     * 가리킬 대상이 하나로 정해지지 않기 때문이다. 다시 저장할 수 있게 된 뒤에도(#198) 경로와
     * 메서드는 그대로다 — 같은 조작("고른 값을 저장한다")이 멱등해진 것이지 다른 조작이 아니다.
     */
    @Operation(
            summary = "학술 활동 모집 선발 저장",
            description =
                    "폼 응답 심사(ACCEPTED)와 팀원 등록·상태 조정(CONFIRMED/WAITLISTED)을"
                            + " **한 트랜잭션**으로 함께 처리한다 — 한 줄이라도 실패하면 전부"
                            + " 되돌아간다. **멱등하다**: 이미 뽑은 신청자를 다시 고르면 같은 값이면"
                            + " 아무 일도 하지 않고 다른 값이면 참가 상태를 그리로 옮긴다(확정↔대기"
                            + " 양방향). 이미 승인된 응답을 다시 심사하지는 않으므로 검토 이력도"
                            + " 늘지 않는다. **학술국장 전용**(ACADEMIC_PROGRAM_MANAGE)이며"
                            + " 스터디장/팀장은 신청자 조회만 할 수 있다(2026-08-23 확정)."
                            + " ptcpSttsCd는 CONFIRMED·WAITLISTED만 받고 그 밖은"
                            + " 400 INVALID_PARTICIPANT_REGISTRATION_STATUS다. 승인으로 갈 수 없는"
                            + " 응답(작성 중·수정요청 대기·반려)은 400"
                            + " INVALID_RESPONSE_STATUS_TRANSITION, 취소(CANCELLED)된 참가자를 다시"
                            + " 고르면 400 INVALID_PARTICIPANT_STATUS_TRANSITION이다(취소 복원은"
                            + " 범위 밖). 모집 시작 전(APPROVED)이면 409 RECRUITMENT_NOT_STARTED."
                            + " **정원 초과는 차단하지 않는다**(참고치) — 화면이 활동 상세의"
                            + " pscpMaxCnt와 응답 명단의 확정 인원을 비교해 경고한다.")
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

    /*
     * 모집 폼(지원서) 조회 (#483). lms "모집 관리"의 '지원서 문항 편집/보기'가 이 경로로 들어온다.
     *
     * 폼 상세를 통째로 품어 내리므로 문항 편집기가 그대로 초안으로 받아 쓴다 —
     * GET /v1/forms/{formId}와 같은 몸통이되 자격이 권한이 아니라 소유권이라는 점만 다르다.
     */
    @Operation(
            summary = "학술 활동 모집 폼(지원서) 조회",
            description =
                    "연결된 모집 폼의 상세를 그대로 싣고(GET /v1/forms/{formId}와 같은 모양)"
                            + " 지금 문항을 고칠 수 있는지(isEditable)를 함께 내린다."
                            + " 자격은 이 활동의 스터디장/팀장 본인 **또는** ACADEMIC_PROGRAM_MANAGE이며"
                            + " 어느 쪽도 아니면 403 FORBIDDEN이다."
                            + " **접수가 시작된 뒤에도 200이다** — 그때는 isEditable만 false이고 문항은"
                            + " 그대로 보인다(화면의 '지원서 문항 보기')."
                            + " 모집 시작 전(APPROVED)에도 열린다 — 신청자 목록과 달리"
                            + " RECRUITMENT_NOT_STARTED를 걸지 않으며, 문항을 채우는 구간이 바로 그때다."
                            + " 폼이 연결되지 않은 활동은 409 FORM_NOT_LINKED,"
                            + " 없는 활동은 404 ACADEMIC_PROGRAM_NOT_FOUND다.")
    @GetMapping("/form")
    public ApiResponse<RecruitmentFormResponse> getRecruitmentForm(
            @PathVariable Long academicProgramId, @CurrentMember MemberEntity requester) {
        return ApiResponse.success(
                academicProgramRecruitmentService.getRecruitmentForm(academicProgramId, requester));
    }

    /*
     * 모집 폼 문항 교체 (#483). 문항 구성은 부분 갱신이 아니라 전체 교체라 PATCH가 아니라
     * PUT이다(AP-06 · PUT /v1/forms/{formId}와 같은 판단).
     *
     * 본문에 접수 기간이 없다 — 받지 않으므로 이 경로로는 덮어쓸 수 없다. 모집 시작·종료 일시를
     * 정하는 길은 POST /v1/academic-programs/{id}/transitions(START_RECRUITMENT) 하나이며
     * 학술국장 전용이다.
     */
    @Operation(
            summary = "학술 활동 모집 폼 문항 수정",
            description =
                    "문항 구성(qitemCpstCn)을 통째로 교체한다. **모집 시작 일시 전까지만 고칠 수 있다** —"
                            + " 학술국장이 모집 관리에서 등록한 그 일시가 지나면 409"
                            + " RECRUITMENT_FORM_NOT_EDITABLE이다(모집 기간을 비워 두고 시작하면 즉시 접수가"
                            + " 열리므로 그 활동에는 편집 구간이 없다). 자격은 조회와 같고, 접수 기간·제목·라벨·다중"
                            + " 응답은 본문에 **받지 않는다** — 모집 일정은 학술국장의 전이 API로만 정한다."
                            + " 문항 구성이 규칙을 어기면 400 INVALID_QUESTION_COMPOSITION,"
                            + " 이미 응답이 있는 폼에서 기존 qitemId를 지우거나 바꾸면 409 QUESTION_ITEM_IN_USE다"
                            + " (접수 전이라 정상 흐름에서는 응답이 없다)."
                            + " 문항 구성이 실제로 바뀐 저장에서만 qitemVer가 1 오르고 그 시점 구성이 이력에 남는다.")
    @PutMapping("/form")
    public ApiResponse<RecruitmentFormResponse> updateRecruitmentFormQuestions(
            @PathVariable Long academicProgramId,
            @Valid @RequestBody RecruitmentFormQuestionUpdateRequest request,
            @CurrentMember MemberEntity actor) {
        return ApiResponse.success(
                academicProgramRecruitmentService.updateRecruitmentFormQuestions(
                        academicProgramId, request, actor));
    }

    /*
     * 모집 일정 조회. 자격은 모집 폼 조회와 같은 소유권 판정이라 애노테이션이 아니라 서비스가
     * 끊는다(클래스 주석의 AND/OR 설명 참고).
     */
    @Operation(
            summary = "학술 활동 모집 일정 조회",
            description =
                    "연결된 모집 폼의 접수 시작·종료 일시와 그것에서 파생된 접수 상태를 내린다."
                            + " 자격은 이 활동의 스터디장/팀장 본인 **또는** ACADEMIC_PROGRAM_MANAGE이며"
                            + " 어느 쪽도 아니면 403 FORBIDDEN이다."
                            + " **모집 시작 전(APPROVED)에도 200이다** — 그때는 두 일시가 null이고"
                            + " 아직 일정이 정해지지 않았다는 뜻이다."
                            + " 폼이 연결되지 않은 활동은 409 FORM_NOT_LINKED,"
                            + " 없는 활동은 404 ACADEMIC_PROGRAM_NOT_FOUND다.")
    @GetMapping("/schedule")
    public ApiResponse<RecruitmentScheduleResponse> getRecruitmentSchedule(
            @PathVariable Long academicProgramId, @CurrentMember MemberEntity requester) {
        return ApiResponse.success(
                academicProgramRecruitmentService.getRecruitmentSchedule(
                        academicProgramId, requester));
    }

    /*
     * 모집 일정 변경 — 모집을 시작한 뒤 접수 기간을 고치는 유일한 경로다.
     *
     * 모집 시작(START_RECRUITMENT)이 이 값을 처음 정하지만 그 전이는 APPROVED에서만 일어나,
     * 이미 ONGOING인 활동의 날짜를 고칠 길이 없었다(ssccops-web#194가 폼 편집의 입력란을 없애며
     * "모집 관리에서 설정한다"고 안내한 뒤로 그 자리가 어디에도 없었다). 전이와 나눈 것은 이
     * 조작이 상태를 바꾸지 않기 때문이며, 그래서 POST /transitions가 아니라 PATCH다.
     *
     * 자격이 조회와 갈린다 — 조회는 리더에게도 열지만 변경은 학술국장 전용이다(#483이 잠가 둔
     * "모집 일정은 학술국장이 정한다"). 그래서 이 핸들러에만 메서드 레벨 애노테이션이 붙는다.
     */
    @Operation(
            summary = "학술 활동 모집 일정 변경",
            description =
                    "모집을 시작한 뒤 접수 시작·종료 일시를 다시 정한다. **학술국장 전용**"
                            + "(ACADEMIC_PROGRAM_MANAGE) — 스터디장/팀장은 조회만 할 수 있다."
                            + " 두 필드는 **전체 교체**이며 null은 '제한 없음'이다: 종료를 비우면"
                            + " 수동으로 마감할 때까지 열리고, 시작을 비우면 곧바로 접수가 열린다."
                            + " 활동 상태는 바뀌지 않는다(폼의 접수 기간만 고친다) — 접수 상태"
                            + " 배지는 바뀐 일시에서 다시 파생돼 응답에 실린다."
                            + " 모집 시작 전(APPROVED)이면 409 RECRUITMENT_NOT_STARTED다"
                            + "(그 구간의 일정은 START_RECRUITMENT가 정한다)."
                            + " 종료가 시작보다 빠르면 400 INVALID_RECEIPT_PERIOD,"
                            + " 폼이 연결되지 않은 활동은 409 FORM_NOT_LINKED다.")
    @PatchMapping("/schedule")
    @RequireAuthority(AuthorityCode.ACADEMIC_PROGRAM_MANAGE)
    public ApiResponse<RecruitmentScheduleResponse> updateRecruitmentSchedule(
            @PathVariable Long academicProgramId,
            @Valid @RequestBody RecruitmentScheduleUpdateRequest request,
            @CurrentMember MemberEntity actor) {
        return ApiResponse.success(
                academicProgramRecruitmentService.updateRecruitmentSchedule(
                        academicProgramId, request, actor));
    }
}
