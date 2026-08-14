package org.sscc.ssccopsserver.domain.member.controller;

import java.net.URI;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.member.dto.MemberProfileResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberSignupRequest;
import org.sscc.ssccopsserver.domain.member.service.MemberService;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.AuthenticatedUser;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 회원 API. 경로 버전 /v1을 쓰고 컨텍스트 경로에 /api를 두지 않는다 (AP-01).
 *
 * 가입은 회원이 필요한 다른 엔드포인트와 달리 @CurrentMember를 쓸 수 없다 — 그 리졸버는
 * 미가입 주체를 403 SIGNUP_REQUIRED로 끊기 때문에, 그대로 두면 가입 자체가 불가능해진다.
 * 그래서 인증 주체를 @AuthenticationPrincipal로 직접 받는다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/members")
public class MemberController {

    private final MemberService memberService;

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
}
