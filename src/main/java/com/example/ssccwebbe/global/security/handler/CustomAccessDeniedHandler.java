package com.example.ssccwebbe.global.security.handler;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import com.example.ssccwebbe.global.apipayload.ApiResponse;
import com.example.ssccwebbe.global.apipayload.code.error.CommonErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class CustomAccessDeniedHandler implements AccessDeniedHandler {

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException)
            throws IOException {

        log.warn("Access denied: {}", accessDeniedException.getMessage());

        // ApiResponse 형식으로 응답 작성
        response.setStatus(CommonErrorCode.FORBIDDEN.getHttpStatus().value());
        response.setContentType("application/json;charset=UTF-8");

        ApiResponse<?> errorResponse =
                ApiResponse.fail(
                        CommonErrorCode.FORBIDDEN, "접근 권한이 없습니다."); // 403 응답, 로그인을 하였으나 권한이 없는 경우
        ObjectMapper mapper = new ObjectMapper();
        response.getWriter().write(mapper.writeValueAsString(errorResponse));
    }
}
