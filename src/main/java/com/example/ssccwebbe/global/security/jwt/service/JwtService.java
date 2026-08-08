package com.example.ssccwebbe.global.security.jwt.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.example.ssccwebbe.global.security.jwt.dto.JwtResponseDto;
import com.example.ssccwebbe.global.security.jwt.dto.RefreshRequestDto;

public interface JwtService {

    /**
     * 소셜 로그인 성공 후 쿠키로 발급해준 Refresh 토큰을 Refresh 토큰과 Access 토큰을 헤더에 담아 한번에 재발급 해주는 메서드
     *
     * @param request HTTP 요청
     * @param response HTTP 응답
     * @return JWT 응답 (Access Token, Refresh Token)
     */
    JwtResponseDto cookie2Header(HttpServletRequest request, HttpServletResponse response);

    /**
     * Refresh 토큰으로 새로운 Access 토큰과 Refresh 토큰을 재발급해주는 로직 (Rotate 포함)
     *
     * @param dto Refresh 요청 DTO
     * @return JWT 응답 (Access Token, Refresh Token)
     */
    JwtResponseDto refreshRotate(RefreshRequestDto dto);

    /**
     * JWT Refresh 토큰 발급 후 저장 메소드
     *
     * @param username 사용자명
     * @param refreshToken Refresh 토큰
     */
    void addRefresh(String username, String refreshToken);

    /**
     * JWT Refresh 존재 확인 메소드
     *
     * @param refreshToken Refresh 토큰
     * @return 존재 여부
     */
    Boolean existsRefresh(String refreshToken);

    /**
     * JWT Refresh 토큰 삭제 메소드
     *
     * @param refreshToken Refresh 토큰
     */
    void removeRefresh(String refreshToken);

    /**
     * 특정 유저 Refresh 토큰 모두 삭제 (탈퇴)
     *
     * @param username 사용자명
     */
    void removeRefreshUser(String username);
}
