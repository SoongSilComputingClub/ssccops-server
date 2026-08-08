package com.example.ssccwebbe.global.security.handler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.util.StringUtils;

import com.example.ssccwebbe.global.apipayload.ApiResponse;
import com.example.ssccwebbe.global.security.jwt.code.JwtErrorCode;
import com.example.ssccwebbe.global.security.jwt.service.JwtService;
import com.example.ssccwebbe.global.security.jwt.util.JwtUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RefreshTokenLogoutHandler implements LogoutHandler {

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

    public RefreshTokenLogoutHandler(JwtService jwtService) {
        this.jwtService = jwtService;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void logout(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) {
        try {
            String body =
                    new BufferedReader(new InputStreamReader(request.getInputStream()))
                            .lines()
                            .reduce("", String::concat);

            // refreshToken이 없는 경우
            if (!StringUtils.hasText(body)) {
                writeErrorResponse(response, JwtErrorCode.REFRESH_TOKEN_REQUIRED);
                return;
            }

            JsonNode jsonNode = objectMapper.readTree(body);
            String refreshToken =
                    jsonNode.has("refreshToken") ? jsonNode.get("refreshToken").asText() : null;

            // refreshToken이 null이거나 빈 문자열인 경우
            if (refreshToken == null || refreshToken.isBlank()) {
                writeErrorResponse(response, JwtErrorCode.REFRESH_TOKEN_REQUIRED);
                return;
            }

            // refreshToken 유효성 검증
            Boolean isValid = JwtUtil.isValid(refreshToken, false);
            if (!isValid) {
                writeErrorResponse(response, JwtErrorCode.INVALID_REFRESH_TOKEN);
                return;
            }

            // Refresh 토큰 삭제
            jwtService.removeRefresh(refreshToken);

        } catch (IOException e) {
            // 요청 본문을 읽을 수 없는 경우
            log.error("Failed to read request body during logout", e);
            try {
                writeErrorResponse(response, JwtErrorCode.REFRESH_TOKEN_REQUIRED);
            } catch (IOException ioException) {
                log.error("Failed to write error response", ioException);
            }
        }
    }

    private void writeErrorResponse(HttpServletResponse response, JwtErrorCode errorCode)
            throws IOException {
        response.setStatus(errorCode.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        ApiResponse<?> errorResponse = ApiResponse.fail(errorCode);
        response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
        response.flushBuffer(); // 응답 커밋
    }
}
