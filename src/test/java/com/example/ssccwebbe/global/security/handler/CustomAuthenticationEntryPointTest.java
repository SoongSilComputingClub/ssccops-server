package com.example.ssccwebbe.global.security.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;

import com.example.ssccwebbe.global.apipayload.code.error.CommonErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class CustomAuthenticationEntryPointTest {

    private CustomAuthenticationEntryPoint authenticationEntryPoint;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private StringWriter stringWriter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws IOException {
        authenticationEntryPoint = new CustomAuthenticationEntryPoint();
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        stringWriter = new StringWriter();
        objectMapper = new ObjectMapper();

        when(response.getWriter()).thenReturn(new PrintWriter(stringWriter));
    }

    @Test
    @DisplayName("인증 실패 시 ApiResponse 형식으로 401 응답")
    void testAuthenticationFailure() throws IOException {
        // given
        BadCredentialsException exception = new BadCredentialsException("자격 증명 실패");

        // when
        authenticationEntryPoint.commence(request, response, exception);

        // then
        String responseBody = stringWriter.toString();
        JsonNode jsonNode = objectMapper.readTree(responseBody);

        assertThat(jsonNode.get("success").asBoolean()).isFalse();
        assertThat(jsonNode.get("code").asText()).isEqualTo(CommonErrorCode.UNAUTHORIZED.getCode());
        assertThat(jsonNode.get("message").asText()).isEqualTo("인증이 필요합니다. 로그인 후 다시 시도해주세요.");
        assertThat(jsonNode.get("data").isNull()).isTrue();
    }

    @Test
    @DisplayName("다양한 인증 예외에 대해 동일한 응답 형식 반환")
    void testVariousAuthenticationExceptions() throws IOException {
        // given
        BadCredentialsException exception = new BadCredentialsException("다른 인증 오류");

        // when
        authenticationEntryPoint.commence(request, response, exception);

        // then
        String responseBody = stringWriter.toString();
        JsonNode jsonNode = objectMapper.readTree(responseBody);

        assertThat(jsonNode.get("success").asBoolean()).isFalse();
        assertThat(jsonNode.get("code").asText()).isEqualTo(CommonErrorCode.UNAUTHORIZED.getCode());
    }

    @Test
    @DisplayName("JSON 응답이 올바른 구조를 가지고 있음")
    void testJsonStructure() throws IOException {
        // given
        BadCredentialsException exception = new BadCredentialsException("인증 실패");

        // when
        authenticationEntryPoint.commence(request, response, exception);

        // then
        String responseBody = stringWriter.toString();
        JsonNode jsonNode = objectMapper.readTree(responseBody);

        // ApiResponse의 4가지 필드 검증
        assertThat(jsonNode.has("success")).isTrue();
        assertThat(jsonNode.has("code")).isTrue();
        assertThat(jsonNode.has("message")).isTrue();
        assertThat(jsonNode.has("data")).isTrue();
    }
}
