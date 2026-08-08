package com.example.ssccwebbe.global.security.handler;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import com.example.ssccwebbe.global.security.jwt.service.JwtService;
import com.example.ssccwebbe.global.security.jwt.util.JwtUtil;

@Component
@Qualifier("SocialSuccessHandler")
public class SocialSuccessHandler implements AuthenticationSuccessHandler {

    private final JwtService jwtService;

    @Value("${frontend.url}")
    private String frontendUrl;

    @Value("${frontend.cookie.secure}")
    private boolean cookieSecure;

    @Value("${frontend.cookie.same-site}")
    private String cookieSameSite;

    @Value("${frontend.cookie.http-only}")
    private boolean cookieHttpOnly;

    public SocialSuccessHandler(@Qualifier("JwtService") JwtService jwtService) {
        this.jwtService = jwtService;
    }

    // 소셜 로그인 성공시 동작 메서드
    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException, ServletException {

        // username, role
        String username = authentication.getName();
        String role = authentication.getAuthorities().iterator().next().getAuthority();

        // JWT(Refresh) 발급 => 소셜 로그인의 경우 브라우저 리다이렉트 방식으로 토큰 발급이 쿠키 방식으로만 가능
        String refreshToken = JwtUtil.createJwt(username, role, false); // role에 이미 "ROLE_" 접두사 포함

        // 발급한 Refresh DB 테이블 저장 (Refresh whitelist)
        jwtService.addRefresh(username, refreshToken);

        // 응답 (ResponseCookie 사용 - SameSite 속성 지원)
        ResponseCookie refreshCookie =
                ResponseCookie.from("refreshToken", refreshToken)
                        .httpOnly(cookieHttpOnly)
                        .secure(cookieSecure)
                        .path("/")
                        .maxAge(10) // 10초 (프론트에서 발급 후 바로 헤더 전환 로직 진행 예정)
                        .sameSite(cookieSameSite) // SameSite 속성 추가
                        .build();

        response.addHeader("Set-Cookie", refreshCookie.toString());
        response.sendRedirect(frontendUrl + "/cookie"); // 프론트 주소로 redirect
    }
}
