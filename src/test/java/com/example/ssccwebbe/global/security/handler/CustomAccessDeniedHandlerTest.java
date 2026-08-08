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
import org.springframework.security.access.AccessDeniedException;

import com.example.ssccwebbe.global.apipayload.code.error.CommonErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class CustomAccessDeniedHandlerTest {

    private CustomAccessDeniedHandler accessDeniedHandler;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private StringWriter stringWriter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws IOException {
        accessDeniedHandler = new CustomAccessDeniedHandler();
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        stringWriter = new StringWriter();
        objectMapper = new ObjectMapper();

        when(response.getWriter()).thenReturn(new PrintWriter(stringWriter));
    }

    @Test
    @DisplayName("접근 거부 시 ApiResponse 형식으로 403 응답")
    void testAccessDenied() throws IOException {
        // given
        AccessDeniedException exception = new AccessDeniedException("접근 거부");

        // when
        accessDeniedHandler.handle(request, response, exception);

        // then
        String responseBody = stringWriter.toString();
        JsonNode jsonNode = objectMapper.readTree(responseBody);

        assertThat(jsonNode.get("success").asBoolean()).isFalse();
        assertThat(jsonNode.get("code").asText()).isEqualTo(CommonErrorCode.FORBIDDEN.getCode());
        assertThat(jsonNode.get("message").asText()).isEqualTo("접근 권한이 없습니다.");
        assertThat(jsonNode.get("data").isNull()).isTrue();
    }

    @Test
    @DisplayName("다양한 접근 거부 예외에 대해 동일한 응답 형식 반환")
    void testVariousAccessDeniedExceptions() throws IOException {
        // given
        AccessDeniedException exception = new AccessDeniedException("권한 부족");

        // when
        accessDeniedHandler.handle(request, response, exception);

        // then
        String responseBody = stringWriter.toString();
        JsonNode jsonNode = objectMapper.readTree(responseBody);

        assertThat(jsonNode.get("success").asBoolean()).isFalse();
        assertThat(jsonNode.get("code").asText()).isEqualTo(CommonErrorCode.FORBIDDEN.getCode());
    }

    @Test
    @DisplayName("JSON 응답이 올바른 구조를 가지고 있음")
    void testJsonStructure() throws IOException {
        // given
        AccessDeniedException exception = new AccessDeniedException("접근 거부");

        // when
        accessDeniedHandler.handle(request, response, exception);

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
