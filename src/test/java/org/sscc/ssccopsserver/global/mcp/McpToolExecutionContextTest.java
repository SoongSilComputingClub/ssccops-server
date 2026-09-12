package org.sscc.ssccopsserver.global.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.sscc.ssccopsserver.global.apipayload.code.error.CommonErrorCode;
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * «도구 실행 스레드에 SecurityContext가 있는가»를 실제로 재 본다 (#384 · ADR-0027 · 분석 문서 7장).
 *
 * <p>#385의 구현 방식이 여기에 걸린다 — 있으면 도구가 {@code SecurityContextHolder}에서 Bearer를 꺼내 자기 REST에 실을 수 있고,
 * 없으면 {@link McpServerConfig}가 전송 컨텍스트에 담아 둔 Authorization 헤더를 써야 한다. 결과는 AGENTS.md «MCP» 절에 적혀 있다.
 *
 * <p>MockMvc가 아니라 실제 포트 + MCP Java 클라이언트(Streamable HTTP)로 부른다 — 세션 초기화·SSE 응답·도구 디스패치가 전부 라이브러리
 * 안에서 일어나는 일이라 흉내 내면 재는 것이 없다. 임시 도구 하나를 이 테스트의 컨텍스트에만 둔다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestJwtDecoderConfig.class, McpToolExecutionContextTest.ProbeToolConfig.class})
class McpToolExecutionContextTest {

    private static final String AUTH_USER_ID = UUID.randomUUID().toString();

    @LocalServerPort private int port;

    @Test
    @DisplayName("도구는 POST /mcp 요청 스레드에서 돌고 SecurityContext와 전송 컨텍스트 양쪽에 신원이 있다")
    void toolRunsWithSecurityContextAndTransportContext() {
        HttpClientStreamableHttpTransport transport =
                HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                        .endpoint(McpProtectedResource.MCP_PATH)
                        .customizeRequest(
                                builder ->
                                        builder.header("Authorization", "Bearer " + AUTH_USER_ID))
                        .build();

        try (McpSyncClient client =
                McpClient.sync(transport).requestTimeout(Duration.ofSeconds(15)).build()) {
            client.initialize();

            McpSchema.CallToolResult result =
                    client.callTool(new McpSchema.CallToolRequest("probe_context", Map.of()));

            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            String text = ((McpSchema.TextContent) result.content().get(0)).text();

            // 1) SecurityContext — 요청 스레드에서 동기로 돌기 때문에 필터체인이 넣은 인증이 그대로 보인다
            assertThat(text).contains("securityContextName=" + AUTH_USER_ID);
            // 2) 전송 컨텍스트 — McpServerConfig가 Authorization 헤더를 담아 둔다 (예비 경로)
            assertThat(text).contains("transportAuthorization=Bearer " + AUTH_USER_ID);
            // 3) 스레드 이름 — 톰캣 요청 스레드이지 Reactor boundedElastic이 아니다
            assertThat(text).contains("thread=http-nio-");
        }
    }

    @Test
    @DisplayName("토큰 없이 initialize 하면 401로 끊긴다 — /mcp는 anyRequest().authenticated()에 걸린다")
    void unauthenticatedInitializeIsRejected() {
        HttpClientStreamableHttpTransport transport =
                HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                        .endpoint(McpProtectedResource.MCP_PATH)
                        .build();

        try (McpSyncClient client =
                McpClient.sync(transport).requestTimeout(Duration.ofSeconds(15)).build()) {
            // 클라이언트는 예외를 두 겹으로 감싼다 — 원인 사슬 어딘가에 우리 401 ApiResponse 본문이 있다
            assertThatThrownBy(client::initialize)
                    .satisfies(
                            e -> {
                                StringBuilder chain = new StringBuilder();
                                for (Throwable t = e; t != null; t = t.getCause()) {
                                    chain.append(t.getMessage()).append(" | ");
                                }
                                assertThat(chain).contains(CommonErrorCode.UNAUTHORIZED.getCode());
                            });
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeToolConfig {

        @Bean
        ProbeTool probeTool() {
            return new ProbeTool();
        }
    }

    /** 임시 도구 — 실행 시점의 스레드·SecurityContext·전송 컨텍스트를 문자열로 돌려준다. */
    static class ProbeTool {

        @McpTool(name = "probe_context", description = "실행 컨텍스트 조사 (테스트 전용)")
        public String probe(McpSyncServerExchange exchange) {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            Object transportAuthorization =
                    exchange.transportContext().get(McpServerConfig.AUTHORIZATION_CONTEXT_KEY);
            return "securityContextName="
                    + (authentication == null ? "none" : authentication.getName())
                    + ";transportAuthorization="
                    + transportAuthorization
                    + ";thread="
                    + Thread.currentThread().getName();
        }
    }
}
