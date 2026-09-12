package org.sscc.ssccopsserver.global.mcp.client;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.global.mcp.McpServerConfig;

import io.modelcontextprotocol.common.McpTransportContext;

/*
 * 도구가 자기 REST를 부를 때 실을 Bearer 토큰 (#385 · ADR-0027).
 *
 * 두 곳을 순서대로 본다.
 * 1. **SecurityContext** — #384가 실제로 쟀듯 도구는 `POST /mcp` 요청 스레드에서 돌아 필터체인이
 *    넣은 `SupabaseAuthenticationToken`이 그대로 보인다. credentials가 검증을 통과한 `Jwt`라
 *    `getTokenValue()`가 곧 원문이다.
 * 2. **전송 컨텍스트** — 그 보장이 Spring AI 내부 결정(`immediateExecution`)이라 버전이 오르며
 *    바뀔 수 있다. `McpServerConfig`가 Authorization 헤더를 `McpTransportContext`에 담아 두므로
 *    SecurityContext가 비어 있으면 거기서 꺼낸다.
 *
 * 둘 다 없으면 도구 오류다 — 토큰 없이 자기 호출을 하면 401이 돌아오고 그 401은 «MCP 세션은
 * 인증됐는데 도구가 인증 실패»라는 이상한 모양으로 모델에게 보인다. 여기서 끊어야 원인이 읽힌다.
 */
@Component
public class BearerTokenSource {

    private static final String BEARER_PREFIX = "Bearer ";

    /** `Authorization` 헤더 값 전체(`Bearer …`)를 돌려준다. */
    public String authorizationHeader(McpTransportContext transportContext) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getCredentials() instanceof Jwt jwt) {
            return BEARER_PREFIX + jwt.getTokenValue();
        }
        if (transportContext != null) {
            Object header = transportContext.get(McpServerConfig.AUTHORIZATION_CONTEXT_KEY);
            if (header instanceof String value && !value.isBlank()) {
                return value;
            }
        }
        throw new McpToolException("UNAUTHENTICATED", "이 MCP 세션에서 인증 토큰을 찾지 못했습니다. 연결을 다시 맺어 주세요.");
    }
}
