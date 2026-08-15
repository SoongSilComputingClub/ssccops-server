package org.sscc.ssccopsserver.domain.member.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.member.dto.MemberGradeResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberStatusResponse;
import org.sscc.ssccopsserver.domain.member.service.MemberService;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 회원 기준 코드 API (#76). 경로 버전 /v1을 쓰고 컨텍스트 경로에 /api를 두지 않는다 (AP-01).
 *
 * MemberController와 컨트롤러를 나눈 것은 리소스가 다르기 때문이다 — 등급·상태는 회원의
 * 하위 자원이 아니라 그 자체로 기준정보이고, 경로도 /v1/members 아래가 아니다.
 *
 * **인증만 요구하고 권한은 요구하지 않는다.** 등급·상태의 코드와 명칭은 개인정보가 아니라
 * 화면이 값을 이름으로 옮길 때 쓰는 사전이며, 회원 관리 권한이 없는 사람도 회원 배지를
 * 그린다. 회원가입 화면이 상태 목록을 필요로 하는데 그때는 아직 회원조차 아니므로
 * @CurrentMember도 걸지 않는다 — 걸면 가입 화면이 셀렉트를 채울 수 없다.
 *
 * 페이징을 두지 않는다. 기준 코드는 열 건 남짓이고 전량을 받아 사전으로 쓰는 목록이다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1")
public class MemberCodeController {

    private final MemberService memberService;

    @Operation(
            summary = "회원 등급 기준 코드 조회",
            description = "mbr_grd 전체를 표시 순번(indct_seqno) 오름차순으로 내린다. 등급 필터·등급 변경 셀렉트가 쓴다.")
    @GetMapping("/member-grades")
    public ApiResponse<List<MemberGradeResponse>> findAllGrades() {
        return ApiResponse.success(memberService.findAllGrades());
    }

    @Operation(
            summary = "회원 상태 기준 코드 조회",
            description =
                    "mbr_stts 전체를 표시 순번(indct_seqno) 오름차순으로 내린다."
                            + " 가입 시 고를 수 없는 상태(탈퇴·제명 등)도 포함한다 — 기준 코드 전체를 내리는"
                            + " 엔드포인트이고, 가입 화면에서 고를 수 있는지는 별개의 규칙이다.")
    @GetMapping("/member-statuses")
    public ApiResponse<List<MemberStatusResponse>> findAllStatuses() {
        return ApiResponse.success(memberService.findAllStatuses());
    }
}
