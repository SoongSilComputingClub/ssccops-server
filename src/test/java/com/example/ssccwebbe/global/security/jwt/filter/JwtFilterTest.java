package com.example.ssccwebbe.global.security.jwt.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.security.core.context.SecurityContextHolder;

import com.example.ssccwebbe.global.security.jwt.code.JwtErrorCode;
import com.example.ssccwebbe.global.security.jwt.util.JwtUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class JwtFilterTest {

    private JwtFilter jwtFilter;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain filterChain;
    private StringWriter stringWriter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        jwtFilter = new JwtFilter();
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        filterChain = mock(FilterChain.class);
        stringWriter = new StringWriter();
        objectMapper = new ObjectMapper();

        when(response.getWriter()).thenReturn(new PrintWriter(stringWriter));

        // SecurityContext 초기화
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Authorization 헤더가 없으면 필터 체인을 계속 진행")
    void testNoAuthorizationHeader() throws Exception {
        // given
        when(request.getHeader("Authorization")).thenReturn(null);

        // when
        jwtFilter.doFilterInternal(request, response, filterChain);

        // then
        verify(filterChain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("Bearer로 시작하지 않는 토큰은 ApiResponse 형식으로 400 에러 응답")
    void testInvalidAuthorizationFormat() throws Exception {
        // given
        when(request.getHeader("Authorization")).thenReturn("InvalidFormat token");

        // when
        jwtFilter.doFilterInternal(request, response, filterChain);

        // then
        verify(filterChain, never()).doFilter(request, response);

        String responseBody = stringWriter.toString();
        JsonNode jsonNode = objectMapper.readTree(responseBody);

        assertThat(jsonNode.get("success").asBoolean()).isFalse();
        assertThat(jsonNode.get("code").asText())
                .isEqualTo(JwtErrorCode.INVALID_TOKEN_FORMAT.getCode());
        assertThat(jsonNode.get("message").asText())
                .isEqualTo(JwtErrorCode.INVALID_TOKEN_FORMAT.getMessage());
        assertThat(jsonNode.get("data").isNull()).isTrue();

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("유효한 JWT 토큰이면 SecurityContext에 인증 정보 설정")
    void testValidToken() throws Exception {
        // given
        String validToken = "valid.jwt.token";
        String username = "testuser";
        String role = "ROLE_USER";

        when(request.getHeader("Authorization")).thenReturn("Bearer " + validToken);

        try (MockedStatic<JwtUtil> mockedJwtUtil = mockStatic(JwtUtil.class)) {
            mockedJwtUtil.when(() -> JwtUtil.isValid(validToken, true)).thenReturn(true);
            mockedJwtUtil.when(() -> JwtUtil.getUsername(validToken)).thenReturn(username);
            mockedJwtUtil.when(() -> JwtUtil.getRole(validToken)).thenReturn(role);

            // when
            jwtFilter.doFilterInternal(request, response, filterChain);

            // then
            verify(filterChain).doFilter(request, response);
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
            assertThat(SecurityContextHolder.getContext().getAuthentication().getName())
                    .isEqualTo(username);
            assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                    .hasSize(1);
        }
    }

    @Test
    @DisplayName("유효하지 않은 JWT 토큰이면 ApiResponse 형식으로 403 에러 응답")
    void testInvalidToken() throws Exception {
        // given
        String invalidToken = "invalid.jwt.token";

        when(request.getHeader("Authorization")).thenReturn("Bearer " + invalidToken);

        try (MockedStatic<JwtUtil> mockedJwtUtil = mockStatic(JwtUtil.class)) {
            mockedJwtUtil.when(() -> JwtUtil.isValid(invalidToken, true)).thenReturn(false);

            // when
            jwtFilter.doFilterInternal(request, response, filterChain);

            // then
            verify(filterChain, never()).doFilter(request, response);

            String responseBody = stringWriter.toString();
            JsonNode jsonNode = objectMapper.readTree(responseBody);

            assertThat(jsonNode.get("success").asBoolean()).isFalse();
            assertThat(jsonNode.get("code").asText())
                    .isEqualTo(JwtErrorCode.INVALID_TOKEN.getCode());
            assertThat(jsonNode.get("message").asText())
                    .isEqualTo(JwtErrorCode.INVALID_TOKEN.getMessage());
            assertThat(jsonNode.get("data").isNull()).isTrue();

            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }
    }

    @Test
    @DisplayName("유효하지 않은 토큰 응답이 올바른 JSON 구조를 가짐")
    void testInvalidTokenJsonStructure() throws Exception {
        // given
        String invalidToken = "invalid.jwt.token";

        when(request.getHeader("Authorization")).thenReturn("Bearer " + invalidToken);

        try (MockedStatic<JwtUtil> mockedJwtUtil = mockStatic(JwtUtil.class)) {
            mockedJwtUtil.when(() -> JwtUtil.isValid(invalidToken, true)).thenReturn(false);

            // when
            jwtFilter.doFilterInternal(request, response, filterChain);

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
}
