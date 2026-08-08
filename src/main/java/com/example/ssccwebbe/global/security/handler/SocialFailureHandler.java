package com.example.ssccwebbe.global.security.handler;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import com.example.ssccwebbe.global.apipayload.ApiResponse;
import com.example.ssccwebbe.global.apipayload.code.error.ErrorCode;
import com.example.ssccwebbe.global.security.code.OAuth2ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component("SocialFailureHandler")
public class SocialFailureHandler implements AuthenticationFailureHandler {

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception)
            throws IOException {

        log.warn("OAuth2 authentication failed: {}", exception.getMessage());

        // OAuth2AuthenticationException인 경우 에러 코드에 따라 적절한 에러 코드 선택
        ErrorCode errorCode;
        if (exception instanceof OAuth2AuthenticationException oauth2Exception) {
            String oauth2ErrorCode = oauth2Exception.getError().getErrorCode();
            if ("unsupported_provider".equals(oauth2ErrorCode)) {
                errorCode = OAuth2ErrorCode.UNSUPPORTED_PROVIDER;
            } else {
                errorCode = OAuth2ErrorCode.AUTHENTICATION_FAILED;
            }
        } else {
            errorCode = OAuth2ErrorCode.AUTHENTICATION_FAILED;
        }

        // ApiResponse 형식으로 응답 작성
        response.setStatus(errorCode.getHttpStatus().value());
        response.setContentType("application/json;charset=UTF-8");

        ApiResponse<?> errorResponse = ApiResponse.fail(errorCode);
        ObjectMapper mapper = new ObjectMapper();
        response.getWriter().write(mapper.writeValueAsString(errorResponse));
    }
}
