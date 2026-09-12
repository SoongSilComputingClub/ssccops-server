package org.sscc.ssccopsserver.global.security.handler;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.apipayload.code.error.CommonErrorCode;
import org.sscc.ssccopsserver.global.mcp.McpProtectedResource;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/*
 * 미인증 401은 항상 ApiResponse 포맷이다(토큰 없음·서명·만료·sub 형식 오류 전부 — SecurityConfig가
 * 리소스 서버와 exceptionHandling 양쪽에 이 핸들러를 건다).
 *
 * **MCP 엔드포인트(`/mcp`)를 향한 401에는 `WWW-Authenticate: Bearer resource_metadata="…"`가 붙는다**
 * (#384 · RFC 9728 · ADR-0026). MCP 클라이언트는 그 URL을 열어 인가 서버를 찾는다. 다른 경로의 401은
 * 그대로다 — 웹이 읽는 계약을 바꾸지 않고, 그 문서의 `resource`가 `/mcp`라 다른 자원의 401에 붙이면
 * 틀린 안내가 된다. mcp-server-security의 EntryPoint를 쓰지 않은 이유는 그쪽의 위임 대상이 본문
 * 없는 BearerTokenAuthenticationEntryPoint로 굳어 있어 ApiResponse 포맷과 갈리기 때문이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CustomAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final McpProtectedResource mcpProtectedResource;

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException)
            throws IOException {

        log.warn("Authentication failed: {}", authException.getMessage());

        if (mcpProtectedResource.isMcpRequest(request)) {
            response.setHeader(
                    HttpHeaders.WWW_AUTHENTICATE, mcpProtectedResource.wwwAuthenticate());
        }

        // ApiResponse 형식으로 응답 작성
        response.setStatus(CommonErrorCode.UNAUTHORIZED.getHttpStatus().value());
        response.setContentType("application/json;charset=UTF-8");

        ApiResponse<?> errorResponse =
                ApiResponse.fail(
                        CommonErrorCode.UNAUTHORIZED,
                        "인증이 필요합니다. 로그인 후 다시 시도해주세요."); // 401 응답, 로그인이 필요한 경로이나 로그인을 하지 않은 경우
        ObjectMapper mapper = new ObjectMapper();
        response.getWriter().write(mapper.writeValueAsString(errorResponse));
    }
}
