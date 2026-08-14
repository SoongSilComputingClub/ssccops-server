package org.sscc.ssccopsserver.domain.auth.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.auth.dto.AuthSessionResponse;
import org.sscc.ssccopsserver.domain.auth.service.AuthService;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.AuthenticatedUser;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 인증 세션 API. 경로 버전 /v1을 쓰고 컨텍스트 경로에 /api를 두지 않는다 (AP-01).
 *
 * 로그인·로그아웃 자체는 Supabase(클라이언트) 책임이라 서버에 엔드포인트가 없다.
 * 서버가 답할 수 있는 것은 "이 토큰이 우리 서비스의 누구인가" 하나뿐이고, 그게 이 API다.
 *
 * 회원이 필요한 다른 엔드포인트와 달리 @CurrentMember를 쓰지 않는다 — 미가입 사용자를
 * 403으로 끊으면 프론트가 가입 화면으로 갈 근거를 얻을 수 없기 때문이다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/auth")
public class AuthController {

    private final AuthService authService;

    @Operation(
            summary = "로그인 세션 조회",
            description =
                    "현재 토큰이 가리키는 로그인 세션을 반환한다. 아직 회원가입을 하지 않은 사용자도 200으로 응답하며,"
                            + " 이때 signedUp=false, member=null이고 가입 화면에 채울 값은 authUser에 담긴다.")
    @GetMapping("/session")
    public ApiResponse<AuthSessionResponse> getSession(
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(authService.getSession(user));
    }
}
