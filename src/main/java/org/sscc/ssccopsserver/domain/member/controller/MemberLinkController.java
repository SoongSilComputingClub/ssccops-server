package org.sscc.ssccopsserver.domain.member.controller;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.member.dto.MemberLinkRequest;
import org.sscc.ssccopsserver.domain.member.dto.MemberProfileResponse;
import org.sscc.ssccopsserver.domain.member.service.MemberService;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.AuthenticatedUser;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 이관 회원 계정 연결 API (#86 · 상위 ssccops#77 · 결정 ssccops#78 A안).
 * 경로 버전 /v1을 쓰고 컨텍스트 경로에 /api를 두지 않는다 (AP-01).
 *
 * ── 왜 MemberController가 아닌가 ────────────────────────────────
 * MemberCodeController·MemberImportController와 같은 판단이다. 저 컨트롤러에는 요구 권한이
 * 서로 다른 핸들러가 이미 여섯 개 섞여 있고, 연결은 그중 어느 것과도 규칙을 공유하지 않는다 —
 * 가입 전 주체가 부르고, 시도 횟수 제한이 걸리며, 실패를 항목별로 나누지 않는다. 한 파일에
 * 더 얹으면 "이 컨트롤러의 규칙"이라고 말할 수 있는 것이 하나도 남지 않는다.
 *
 * ── 왜 @CurrentMember가 아닌가 ─────────────────────────────────
 * 가입(/signup)과 정확히 같은 이유다. 그 리졸버는 미가입 주체를 403 SIGNUP_REQUIRED로 끊는데,
 * 연결을 요청하는 사람은 정의상 아직 회원이 아니다 — 걸면 연결 자체가 불가능해진다. 그래서
 * 인증 주체를 @AuthenticationPrincipal로 직접 받는다.
 *
 * @RequireAuthority도 없다. 명부에 있는 본인이 자기 계정을 붙이는 일이라 권한으로 막을 대상이
 * 아니며, 대신 본인 확인(3종 일치)과 시도 횟수 제한이 그 자리를 대신한다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/members")
public class MemberLinkController {

    private final MemberService memberService;

    /*
     * **201이 아니라 200이다.** 가입과 응답 모양이 같아 헷갈리기 쉬운데, 이 요청은 자원을 만들지
     * 않는다 — 명부 행은 CSV 이관(#85)이 이미 만들어 두었고 여기서는 auth_user_id 한 컬럼이
     * 채워질 뿐이다. Location으로 가리킬 '새로 생긴 것'이 없다.
     */
    @Operation(
            summary = "이관 회원 계정 연결",
            description =
                    "CSV로 이관된 명부의 기존 회원에 지금 로그인한 소셜 계정을 연결한다."
                            + " 학번·회원명·연락처 3종이 **모두** 일치하고 아직 계정이 붙지 않은 회원이"
                            + " 정확히 한 건일 때만 연결된다. 연락처는 하이픈 유무를 가리지 않으며"
                            + " 회원명은 앞뒤 공백을 지우고 비교한다."
                            + " 회원 행을 새로 만들지 않으므로 기수·등급·상태·역할은 명부의 값 그대로이고,"
                            + " 응답은 가입·세션 조회와 같은 모양이라 연결 직후 세션을 다시 조회할 필요가 없다."
                            + " 일치하는 회원이 없으면 404 MEMBER_LINK_FAILED이며 **어느 항목이 틀렸는지는"
                            + " 응답에 담기지 않는다** — 학번을 바꿔 가며 명부를 훑는 것을 막기 위해서다."
                            + " 이미 다른 계정과 연결된 회원은 409 MEMBER_ALREADY_LINKED,"
                            + " 이미 가입을 마친 계정의 요청은 409 ALREADY_SIGNED_UP,"
                            + " 실패가 반복되면 429 TOO_MANY_LINK_ATTEMPTS다.")
    @PostMapping("/link")
    public ApiResponse<MemberProfileResponse> link(
            @Valid @RequestBody MemberLinkRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {

        return ApiResponse.success(memberService.link(user, request));
    }
}
