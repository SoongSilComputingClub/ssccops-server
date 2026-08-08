package com.example.ssccwebbe.global.security.jwt.service;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ssccwebbe.domain.user.entity.UserRefreshEntity;
import com.example.ssccwebbe.domain.user.repository.UserRefreshRepository;
import com.example.ssccwebbe.global.apipayload.exception.GeneralException;
import com.example.ssccwebbe.global.security.jwt.code.JwtErrorCode;
import com.example.ssccwebbe.global.security.jwt.dto.JwtResponseDto;
import com.example.ssccwebbe.global.security.jwt.dto.RefreshRequestDto;
import com.example.ssccwebbe.global.security.jwt.util.JwtUtil;

import lombok.RequiredArgsConstructor;

@Service("JwtService")
@RequiredArgsConstructor
public class JwtServiceImpl implements JwtService {

    private final UserRefreshRepository userRefreshRepository;

    @Value("${frontend.cookie.secure}")
    private boolean cookieSecure;

    // 소셜 로그인 성공 후 쿠키로 발급해준 Refresh 토큰을 Refresh 토큰과 Access 토큰을  헤더에 담아 한번에 재발급 해주는 메서드
    @Transactional
    public JwtResponseDto cookie2Header(HttpServletRequest request, HttpServletResponse response) {

        // 쿠키 리스트
        Cookie[] cookies = request.getCookies();

        // 쿠키가 없는 경우
        if (cookies == null) {
            throw new GeneralException(JwtErrorCode.COOKIE_NOT_FOUND);
        }

        // Refresh 토큰 획득
        String refreshToken = null;
        for (Cookie cookie : cookies) {
            if ("refreshToken".equals(cookie.getName())) {
                refreshToken = cookie.getValue();
                break;
            }
        }

        // Refresh 토큰이 없는 경우
        if (refreshToken == null) {
            throw new GeneralException(JwtErrorCode.REFRESH_TOKEN_COOKIE_NOT_FOUND);
        }

        // Refresh 토큰 검증
        Boolean isValid = JwtUtil.isValid(refreshToken, false);
        if (!isValid) {
            throw new GeneralException(JwtErrorCode.INVALID_REFRESH_TOKEN);
        }

        // 정보 추출
        String username = JwtUtil.getUsername(refreshToken);
        String role = JwtUtil.getRole(refreshToken);

        // 토큰 생성
        String newAccessToken = JwtUtil.createJwt(username, role, true);
        String newRefreshToken = JwtUtil.createJwt(username, role, false);

        // 기존 Refresh 토큰 DB 삭제 후 신규 추가
        UserRefreshEntity newRefreshEntity =
                UserRefreshEntity.builder().username(username).refresh(newRefreshToken).build();

        removeRefresh(refreshToken);
        userRefreshRepository.flush(); // 같은 트랜잭션 내부라 : 삭제 -> 생성 문제 해결
        userRefreshRepository.save(newRefreshEntity);

        // 기존 쿠키 제거
        Cookie refreshCookie = new Cookie("refreshToken", null);
        refreshCookie.setHttpOnly(true);
        refreshCookie.setSecure(cookieSecure);
        refreshCookie.setPath("/");
        refreshCookie.setMaxAge(10);
        response.addCookie(refreshCookie);

        return new JwtResponseDto(newAccessToken, newRefreshToken);
    }

    // Refresh 토큰으로 새로운 Access 토큰 과 Refresh 토큰을 재발급해주는 로직 (Rotate 포함)
    @Transactional
    public JwtResponseDto refreshRotate(RefreshRequestDto dto) {

        String refreshToken = dto.getRefreshToken();

        // Refresh 토큰 검증
        Boolean isValid = JwtUtil.isValid(refreshToken, false);
        if (!isValid) {
            throw new GeneralException(JwtErrorCode.INVALID_REFRESH_TOKEN);
        }

        // RefreshEntity 존재 확인 (화이트리스트)
        if (!existsRefresh(refreshToken)) {
            throw new GeneralException(JwtErrorCode.REFRESH_TOKEN_NOT_IN_WHITELIST);
        }

        // 정보 추출
        String username = JwtUtil.getUsername(refreshToken);
        String role = JwtUtil.getRole(refreshToken);

        // 토큰 생성
        String newAccessToken = JwtUtil.createJwt(username, role, true);
        String newRefreshToken = JwtUtil.createJwt(username, role, false);

        // 기존 Refresh 토큰 DB 삭제 후 신규 추가
        UserRefreshEntity newRefreshEntity =
                UserRefreshEntity.builder().username(username).refresh(newRefreshToken).build();

        removeRefresh(refreshToken);
        userRefreshRepository.save(newRefreshEntity);

        return new JwtResponseDto(newAccessToken, newRefreshToken);
    }

    // JWT Refresh 토큰 발급 후 저장 메소드
    @Transactional
    public void addRefresh(String username, String refreshToken) {
        UserRefreshEntity entity =
                UserRefreshEntity.builder().username(username).refresh(refreshToken).build();

        userRefreshRepository.save(entity);
    }

    // JWT Refresh 존재 확인 메소드
    @Transactional(readOnly = true)
    public Boolean existsRefresh(String refreshToken) {
        return userRefreshRepository.existsByRefresh(refreshToken);
    }

    // JWT Refresh 토큰 삭제 메소드
    @Transactional
    public void removeRefresh(String refreshToken) {
        userRefreshRepository.deleteByRefresh(refreshToken);
    }

    // 특정 유저 Refresh 토큰 모두 삭제 (탈퇴)
    @Transactional
    public void removeRefreshUser(String username) {
        userRefreshRepository.deleteByUsername(username);
    }
}
