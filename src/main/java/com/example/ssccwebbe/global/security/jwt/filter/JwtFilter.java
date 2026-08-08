package com.example.ssccwebbe.global.security.jwt.filter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.example.ssccwebbe.global.apipayload.ApiResponse;
import com.example.ssccwebbe.global.security.jwt.code.JwtErrorCode;
import com.example.ssccwebbe.global.security.jwt.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

// JWT 검증 필터
@Slf4j
public class JwtFilter extends OncePerRequestFilter {

    // Request 헤더에서 accessToken 을 추출하여 인증, 인가를 처리함
    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String authorization = request.getHeader("Authorization");
        if (authorization == null) {
            log.debug("[JwtFilter] No Authorization header - URI: {}", request.getRequestURI());
            filterChain.doFilter(request, response);
            return;
        }

        if (!authorization.startsWith("Bearer ")) {
            log.warn("[JwtFilter] Invalid token format - URI: {}", request.getRequestURI());
            // ApiResponse 형식으로 응답 작성
            response.setStatus(JwtErrorCode.INVALID_TOKEN_FORMAT.getHttpStatus().value());
            response.setContentType("application/json;charset=UTF-8");

            ApiResponse<?> errorResponse = ApiResponse.fail(JwtErrorCode.INVALID_TOKEN_FORMAT);
            ObjectMapper mapper = new ObjectMapper();
            response.getWriter().write(mapper.writeValueAsString(errorResponse));
            return;
        }

        // 토큰 파싱
        String accessToken = authorization.split(" ")[1];

        // 토큰 검증
        if (JwtUtil.isValid(accessToken, true)) {

            String username = JwtUtil.getUsername(accessToken);
            String role = JwtUtil.getRole(accessToken);

            log.debug(
                    "[JwtFilter] Authentication successful - Username: {}, Role: {}",
                    username,
                    role);

            List<GrantedAuthority> authorities =
                    Collections.singletonList(new SimpleGrantedAuthority(role));

            Authentication auth =
                    new UsernamePasswordAuthenticationToken(username, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(auth);

            filterChain.doFilter(request, response);

        } else {
            log.warn("[JwtFilter] Token validation failed - URI: {}", request.getRequestURI());
            // ApiResponse 형식으로 응답 작성
            response.setStatus(JwtErrorCode.INVALID_TOKEN.getHttpStatus().value());
            response.setContentType("application/json;charset=UTF-8");

            ApiResponse<?> errorResponse = ApiResponse.fail(JwtErrorCode.INVALID_TOKEN);
            ObjectMapper mapper = new ObjectMapper();
            response.getWriter().write(mapper.writeValueAsString(errorResponse));
        }
    }
}
