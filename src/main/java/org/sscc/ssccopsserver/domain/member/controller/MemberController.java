package org.sscc.ssccopsserver.domain.member.controller;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.dto.AssignableMemberResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberDetailResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberGradeChangeRequest;
import org.sscc.ssccopsserver.domain.member.dto.MemberGradeChangeResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberProfileResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberSearchCondition;
import org.sscc.ssccopsserver.domain.member.dto.MemberSearchResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberSelfUpdateRequest;
import org.sscc.ssccopsserver.domain.member.dto.MemberSignupRequest;
import org.sscc.ssccopsserver.domain.member.dto.MemberStatusChangeRequest;
import org.sscc.ssccopsserver.domain.member.dto.MemberStatusChangeResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberSummaryResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberUpdateRequest;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.service.MemberChangeService;
import org.sscc.ssccopsserver.domain.member.service.MemberService;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.AuthenticatedUser;
import org.sscc.ssccopsserver.global.security.authorization.RequireAuthority;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMember;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 회원 API. 경로 버전 /v1을 쓰고 컨텍스트 경로에 /api를 두지 않는다 (AP-01).
 *
 * 가입은 회원이 필요한 다른 엔드포인트와 달리 @CurrentMember를 쓸 수 없다 — 그 리졸버는
 * 미가입 주체를 403 SIGNUP_REQUIRED로 끊기 때문에, 그대로 두면 가입 자체가 불가능해진다.
 * 그래서 인증 주체를 @AuthenticationPrincipal로 직접 받는다.
 *
 * **@RequireAuthority는 클래스가 아니라 메서드에 건다** (#76). 이 컨트롤러에는 요구 권한이
 * 서로 다른 핸들러가 섞여 있다 — 가입(/signup)은 아직 회원이 아닌 사람이 부르고, 담당자
 * 후보(/assignable)는 회원 관리 권한 없이 업무를 등록하는 사람이 부른다. 클래스에 걸면
 * 그 둘이 함께 막히고, 가입은 아예 통과할 수 없는 엔드포인트가 된다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/members")
public class MemberController {

    private final MemberService memberService;

    /*
     * 등급·상태 변경은 조회·가입과 다른 빈이다 (#78). 나눈 이유는 MemberChangeService 주석에
     * 있다 — 운영 도메인(SubWorkService)이 필요한데 그쪽이 이미 MemberService를 주입받고 있어
     * 한 빈에 두면 생성자 주입이 순환한다.
     */
    private final MemberChangeService memberChangeService;

    @Operation(
            summary = "회원가입",
            description =
                    "인증만 마친 사용자를 정식 회원으로 등록한다. 등급은 임시회원(TEMP)으로 고정되며 학번·이름 등 프로필만 받는다."
                            + " 계정 식별자와 이메일은 토큰에서 가져오므로 요청 본문에 넣지 않는다."
                            + " 응답 본문은 세션 조회(GET /v1/auth/session)의 member 블록과 같은 모양이라"
                            + " 가입 직후 세션을 다시 조회할 필요가 없다."
                            + " 이미 가입한 계정이면 409 ALREADY_SIGNED_UP, 학번이 이미 등록돼 있으면 409"
                            + " STUDENT_NUMBER_DUPLICATED로 응답한다.")
    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<MemberProfileResponse>> signUp(
            @Valid @RequestBody MemberSignupRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        MemberProfileResponse response = memberService.signUp(user, request);
        URI location = URI.create("/v1/members/" + response.memberId());
        return ResponseEntity.created(location).body(ApiResponse.created(response));
    }

    /*
     * 회원 목록 조회 (#76). 회원 관리 화면의 표가 이 호출 하나로 채워진다.
     *
     * 조건은 개별 @RequestParam으로 늘어놓지 않고 record 하나로 받는다 — 필터가 늘 때마다
     * 시그니처가 자라는 것을 막는다. 값 해석(기준 코드·커서)은 DTO와 서비스가 맡으므로
     * 여기서 분기하지 않는다 (LY-02 · WorkController와 같은 방식).
     *
     * 목록이므로 응답은 data 배열과 page 봉투 두 갈래다 (AP-11).
     */
    @Operation(
            summary = "회원 목록 조회",
            description =
                    "이름·학번 부분일치 검색과 등급·상태 필터, 정렬(mbrNm·genNo·joinYmd·mdfcnDt, '-'는 내림차순),"
                            + " 커서 페이징을 지원한다. 등급·상태는 코드와 명칭을 함께 내리며 현재 역할도 함께"
                            + " 싣는다. 기준 코드 밖의 필터 값은 400 INVALID_CODE_VALUE다.")
    @RequireAuthority(AuthorityCode.MEMBER_MANAGE)
    @GetMapping
    public ApiResponse<List<MemberSummaryResponse>> searchMembers(
            @Valid @ModelAttribute MemberSearchCondition condition) {
        MemberSearchResponse result = memberService.searchMembers(condition);
        return ApiResponse.success(result.members(), result.page());
    }

    /*
     * 담당자 후보 조회 (#76). 업무·회의 등록 화면의 담당자 선택 칩이 쓴다.
     *
     * **경로가 /{mbrId}보다 먼저 선언돼 있어야 하는 것은 아니다** — 스프링은 리터럴 세그먼트를
     * 경로 변수보다 우선하므로 'assignable'이 단건 조회로 새지 않는다.
     *
     * 목록·단건과 달리 MEMBER_MANAGE를 요구하지 않는다. 담당자를 고르는 일은 회원 관리와
     * 다른 권한 축이고, 업무를 등록할 수 있는 사람이 회원 관리 권한까지 가질 이유가 없다.
     * 그 대신 응답에서 연락처·이메일·학번을 뺀다 (AssignableMemberResponse 주석).
     *
     * 페이징을 두지 않는다 — 선택 칩의 드롭다운이라 한 번에 받아 그 자리에서 걸러 쓰는
     * 목록이고, 동아리 회원 수 규모에서 나누어 받을 이유가 없다 (#37의 응답 목록과 같은 판단).
     */
    @Operation(
            summary = "담당자 후보 조회",
            description =
                    "담당자·회의 책임자로 지정할 수 있는 회원 목록. 탈퇴·제명 회원은 빠진다."
                            + " 권한 없이 부를 수 있는 목록이라 연락처·이메일·학번은 내리지 않는다.")
    @GetMapping("/assignable")
    public ApiResponse<List<AssignableMemberResponse>> findAssignableMembers() {
        return ApiResponse.success(memberService.findAssignableMembers());
    }

    /*
     * 회원 단건 조회 (#76). 프로필·현재 역할·최근 변경 이력 3건을 한 번에 내린다.
     *
     * 없는 회원은 404 MEMBER_NOT_FOUND이며 권한이 없으면 403이다 — 404로 감추지 않는다
     * (VR-M10). 내부 운영 도구라 자원의 존재를 숨길 이유가 없다.
     */
    @Operation(
            summary = "회원 단건 조회",
            description =
                    "회원 프로필과 현재 역할, 최근 변경 이력 3건(등급·상태를 섞어 기록 시각 역순)을 내린다."
                            + " 현재 역할은 역할 시작일 <= 오늘 <= 종료일(NULL이면 무기한)인 배정이다.")
    @RequireAuthority(AuthorityCode.MEMBER_MANAGE)
    @GetMapping("/{memberId}")
    public ApiResponse<MemberDetailResponse> getMember(@PathVariable Long memberId) {
        return ApiResponse.success(memberService.getMemberDetail(memberId));
    }

    /*
     * 본인 프로필 수정 (#77).
     *
     * **단건 수정보다 먼저 선언할 필요는 없다** — 스프링이 리터럴 세그먼트를 경로 변수보다
     * 우선하므로 'me'가 회원 식별자 경로로 새지 않는다(/assignable과 같다).
     *
     * MEMBER_MANAGE를 요구하지 않는다. 자기 연락처를 고치는 데 회원 관리 권한이 필요하다면
     * 대부분의 회원은 자기 정보를 영영 고칠 수 없다. 대신 대상이 @CurrentMember 하나로 고정돼
     * 있어 남의 행에 닿을 자리가 없고, 미가입 주체는 그 리졸버가 403 SIGNUP_REQUIRED로 끊는다.
     *
     * 응답이 MemberProfileResponse인 것은 가입·세션 조회와 같은 모양을 쓰기 위해서다 —
     * 저장 직후 웹이 세션을 다시 조회하지 않아도 된다.
     */
    @Operation(
            summary = "본인 프로필 수정",
            description =
                    "인증 주체 본인의 이름·학과·학년·연락처를 고친다. 대상은 언제나 본인이라 경로에 회원 식별자가 없다."
                            + " 기수와 이메일은 이 경로로 바꿀 수 없다 — 기수는 운영진이 배정하는 값이고,"
                            + " 이메일은 인증 계정에서 오므로 본인이 바꾸면 로그인 계정과 갈린다."
                            + " 등급·상태·학번도 바꿀 수 없다(요청 본문에 필드 자체가 없다)."
                            + " 재학 회원이 학과·학년을 비우면 400 VALIDATION_FAILED다."
                            + " 응답은 세션 조회(GET /v1/auth/session)의 member 블록과 같은 모양이라"
                            + " 저장 직후 세션을 다시 조회할 필요가 없다.")
    @PatchMapping("/me")
    public ApiResponse<MemberProfileResponse> updateMyProfile(
            @CurrentMember MemberEntity member,
            @Valid @RequestBody MemberSelfUpdateRequest request) {
        return ApiResponse.success(memberService.updateMyProfile(member.getId(), request));
    }

    /*
     * 운영진의 회원 정보 수정 (#77).
     *
     * 바꿀 수 있는 필드는 요청 DTO가 정한다 — 등급·상태(#78)·학번은 애초에 담기지 않으므로
     * 여기에 걸러내는 코드가 없다. 없는 회원은 404, 재학 회원의 학과·학년 누락은 400이다.
     */
    @Operation(
            summary = "회원 정보 수정",
            description =
                    "기수·이름·학과·학년·연락처·이메일을 고친다. 등급·상태는 변경 이력을 함께 남겨야 해"
                            + " 전용 API가 따로 있고, 학번·가입일·계정 식별자는 바꿀 수 없다"
                            + " (요청 본문에 필드 자체가 없어 넣어도 무시된다)."
                            + " PATCH이지만 본문은 한 벌 전체이며, 생략한 선택 필드는 비우는 것으로 본다."
                            + " 재학 회원이 학과·학년을 비우면 400 VALIDATION_FAILED, 없는 회원은 404다.")
    @RequireAuthority(AuthorityCode.MEMBER_MANAGE)
    @PatchMapping("/{memberId}")
    public ApiResponse<MemberDetailResponse> updateMember(
            @PathVariable Long memberId, @Valid @RequestBody MemberUpdateRequest request) {
        return ApiResponse.success(memberService.updateMember(memberId, request));

    /*
     * 회원 등급 변경 (#78). mbr 갱신과 mbr_grd_hstry INSERT가 한 트랜잭션이다.
     *
     * **변경자를 요청 본문으로 받지 않는다.** @CurrentMember로 인증 주체에서 가져오며, 그래야
     * 이력의 chnrg_mbr_id가 증거가 된다 (LY-05 — 하위 업무 전이의 performer와 같은 자리).
     *
     * 회원 정보 수정(PATCH)에 등급 필드를 두지 않고 전용 경로를 파는 것은, 같은 API에 섞으면
     * 이력 없이 등급이 바뀌는 경로가 반드시 생기기 때문이다.
     *
     * 201이 아니라 200인 것은 이 요청의 결과가 '새 자원'이 아니기 때문이다 — 이력 행이 하나
     * 생기지만 그것을 가리키는 조회 경로가 없어 Location에 실을 URI가 없고, 화면이 받는 것은
     * 바뀐 회원이다 (하위 업무 상태 전이 POST .../transitions와 같은 판단).
     */
    @Operation(
            summary = "회원 등급 변경",
            description =
                    "회원의 등급을 바꾸고 변경 이력(mbr_grd_hstry)을 한 트랜잭션에서 남긴다."
                            + " 변경자는 요청 본문이 아니라 토큰에서 가져온다."
                            + " 적용 일자를 생략하면 오늘이며 미래 일자는 400 VALIDATION_FAILED,"
                            + " 지금과 같은 등급은 400 NO_CHANGE, 기준 코드 밖의 등급은 400"
                            + " INVALID_CODE_VALUE, 없는 회원은 404다."
                            + " 응답은 변경 후 회원 상세이며 warnings는 항상 비어 있다(상태 변경과 같은 모양).")
    @RequireAuthority(AuthorityCode.MEMBER_MANAGE)
    @PostMapping("/{memberId}/grade-changes")
    public ApiResponse<MemberGradeChangeResponse> changeGrade(
            @PathVariable Long memberId,
            @Valid @RequestBody MemberGradeChangeRequest request,
            @CurrentMember MemberEntity changer) {
        return ApiResponse.success(memberChangeService.changeGrade(memberId, request, changer));
    }

    /*
     * 회원 상태 변경 (#78). 등급 변경과 같은 규칙이며 다른 점은 두 가지다.
     *
     * 종료 예정일(sttsEndPrnmntYmd)은 휴학·군휴학에만 실을 수 있고 그 밖의 상태에 실려 오면
     * 400이다 — 조용히 버리지 않는 근거는 MemberStatusChangeRequest 주석에 있다.
     *
     * 탈퇴·제명으로 전이할 때 역할을 끝내거나 담당 업무를 정리하지 **않는다.** 대신 남아 있는
     * 것들이 warnings로 실려 화면이 사람에게 알린다 (MemberChangeWarningResponse 주석).
     */
    @Operation(
            summary = "회원 상태 변경",
            description =
                    "회원의 상태를 바꾸고 변경 이력(mbr_stts_hstry)을 한 트랜잭션에서 남긴다."
                            + " 종료 예정일은 휴학·군휴학에만 지정할 수 있다(그 밖의 상태는 400 VALIDATION_FAILED)."
                            + " 탈퇴·제명으로 바꿔도 역할·담당 업무를 자동으로 정리하지 않으며,"
                            + " 남아 있는 현재 역할·담당 하위 업무 건수를 warnings로 함께 내린다."
                            + " 오류는 등급 변경과 같다.")
    @RequireAuthority(AuthorityCode.MEMBER_MANAGE)
    @PostMapping("/{memberId}/status-changes")
    public ApiResponse<MemberStatusChangeResponse> changeStatus(
            @PathVariable Long memberId,
            @Valid @RequestBody MemberStatusChangeRequest request,
            @CurrentMember MemberEntity changer) {
        return ApiResponse.success(memberChangeService.changeStatus(memberId, request, changer));
    }
}
