package com.example.ssccwebbe.global.security.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

import com.example.ssccwebbe.global.security.code.OAuth2ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class SocialFailureHandlerTest {

    private SocialFailureHandler socialFailureHandler;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private StringWriter stringWriter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws IOException {
        socialFailureHandler = new SocialFailureHandler();
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        stringWriter = new StringWriter();
        objectMapper = new ObjectMapper();

        when(response.getWriter()).thenReturn(new PrintWriter(stringWriter));
    }

    @Test
    @DisplayName("지원하지 않는 소셜 로그인 예외 발생 시 UNSUPPORTED_PROVIDER 에러 응답")
    void testUnsupportedProvider() throws IOException {
        // given
        OAuth2Error error = new OAuth2Error("unsupported_provider", "지원하지 않는 소셜 로그인입니다.", null);
        OAuth2AuthenticationException exception = new OAuth2AuthenticationException(error);

        // when
        socialFailureHandler.onAuthenticationFailure(request, response, exception);

        // then
        String responseBody = stringWriter.toString();
        JsonNode jsonNode = objectMapper.readTree(responseBody);

        assertThat(jsonNode.get("success").asBoolean()).isFalse();
        assertThat(jsonNode.get("code").asText())
                .isEqualTo(OAuth2ErrorCode.UNSUPPORTED_PROVIDER.getCode());
        assertThat(jsonNode.get("message").asText())
                .isEqualTo(OAuth2ErrorCode.UNSUPPORTED_PROVIDER.getMessage());
    }

    @Test
    @DisplayName("일반 OAuth2 인증 예외 발생 시 AUTHENTICATION_FAILED 에러 응답")
    void testAuthenticationFailed() throws IOException {
        // given
        OAuth2Error error = new OAuth2Error("authentication_failed", "OAuth2 인증에 실패했습니다.", null);
        OAuth2AuthenticationException exception = new OAuth2AuthenticationException(error);

        // when
        socialFailureHandler.onAuthenticationFailure(request, response, exception);

        // then
        String responseBody = stringWriter.toString();
        JsonNode jsonNode = objectMapper.readTree(responseBody);

        assertThat(jsonNode.get("success").asBoolean()).isFalse();
        assertThat(jsonNode.get("code").asText())
                .isEqualTo(OAuth2ErrorCode.AUTHENTICATION_FAILED.getCode());
        assertThat(jsonNode.get("message").asText())
                .isEqualTo(OAuth2ErrorCode.AUTHENTICATION_FAILED.getMessage());
    }

    @Test
    @DisplayName("비-OAuth2 인증 예외 발생 시 AUTHENTICATION_FAILED 에러 응답")
    void testNonOAuth2AuthenticationException() throws IOException {
        // given
        BadCredentialsException exception = new BadCredentialsException("인증 실패");

        // when
        socialFailureHandler.onAuthenticationFailure(request, response, exception);

        // then
        String responseBody = stringWriter.toString();
        JsonNode jsonNode = objectMapper.readTree(responseBody);

        assertThat(jsonNode.get("success").asBoolean()).isFalse();
        assertThat(jsonNode.get("code").asText())
                .isEqualTo(OAuth2ErrorCode.AUTHENTICATION_FAILED.getCode());
        assertThat(jsonNode.get("message").asText())
                .isEqualTo(OAuth2ErrorCode.AUTHENTICATION_FAILED.getMessage());
    }

    @Test
    @DisplayName("응답 상태 코드와 Content-Type이 올바르게 설정됨")
    void testResponseStatusAndContentType() throws IOException {
        // given
        OAuth2AuthenticationException exception =
                new OAuth2AuthenticationException("지원하지 않는 소셜 로그인입니다.");

        // when
        socialFailureHandler.onAuthenticationFailure(request, response, exception);

        // then
        verify(response).setStatus(HttpStatus.UNAUTHORIZED.value());
        verify(response).setContentType("application/json;charset=UTF-8");
    }
}
