package org.sscc.ssccopsserver.global.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * «도구 실행 스레드에 SecurityContext가 있는가»를 실제로 재 본다 (#384 · ADR-0027 · 분석 문서 7장) — 그리고 전송이
 * stateless인지(#393).
 *
 * <p>#385의 구현 방식이 여기에 걸린다 — 있으면 도구가 {@code SecurityContextHolder}에서 Bearer를 꺼내 자기 REST에 실을 수 있고,
 * 없으면 {@link McpServerConfig}가 전송 컨텍스트에 담아 둔 Authorization 헤더를 써야 한다. 결과는 AGENTS.md «MCP» 절에 적혀 있다.
 *
 * <p>MockMvc가 아니라 실제 포트 + MCP Java 클라이언트(Streamable HTTP)로 부른다 — 초기화·응답 형식·도구 디스패치가 전부 라이브러리 안에서
 * 일어나는 일이라 흉내 내면 재는 것이 없다. 임시 도구 하나를 이 테스트의 컨텍스트에만 둔다. 세션이 없다는 것(#393)은 MCP 클라이언트로는 볼 수 없어(클라이언트가
 * initialize를 알아서 먼저 보낸다) 그 둘은 JDK HttpClient로 JSON-RPC를 직접 쏜다.
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

    /*
     * #393 — 2026-09-13 dev 실측: 재배포 뒤 Claude Code가 옛 Mcp-Session-Id로 POST → 404 → «failed to
     * connect»로 굳음. stateless 전송은 세션을 내지도 요구하지도 않으므로 initialize 없이, 그리고 서버가
     * 모르는 세션 id를 달고 와도 tools/call이 그대로 처리돼야 한다.
     */
    @Test
    @DisplayName("stateless — initialize 없이, 서버가 모르는 Mcp-Session-Id를 달고 온 tools/call도 200이다")
    void toolsCallWithoutInitializeAndWithStaleSessionId() throws Exception {
        HttpResponse<String> response =
                post(
                        """
                        {"jsonrpc":"2.0","id":1,"method":"tools/call",
                         "params":{"name":"probe_context","arguments":{}}}
                        """,
                        "Mcp-Session-Id",
                        "stale-session-from-previous-deploy");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Mcp-Session-Id")).isEmpty();
        assertThat(response.body())
                .contains("securityContextName=" + AUTH_USER_ID)
                .doesNotContain("\"error\"");
    }

    /*
     * MCP Java SDK의 전송은 4xx·5xx 본문에 McpError(RuntimeException) 객체를 그대로 싣는다 — Jackson이
     * 그것을 직렬화하면 stackTrace가 프레임 단위로 나간다(실측한 404 본문이 그것이었다). McpServerConfig의
     * 라우터 필터가 ApiResponse 오류 봉투로 바꿔 내는지 본다.
     */
    @Test
    @DisplayName("잘못된 JSON-RPC 본문 — 400이고 본문은 ApiResponse 봉투다, 스택트레이스가 없다")
    void malformedBodyIsRejectedWithoutStackTrace() throws Exception {
        HttpResponse<String> response = post("{not json");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(v -> assertThat(v).startsWith("application/json"));
        assertThat(response.body())
                .contains("\"success\":false")
                .contains("\"code\":\"" + CommonErrorCode.INVALID_BODY.getCode() + "\"")
                .doesNotContain("stackTrace", "\"cause\"", "suppressed", "handlePost");
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

    /* Accept는 stateless 전송이 요구하는 둘(json + event-stream)을 다 싣는다 — 하나라도 빠지면 400이다 */
    private HttpResponse<String> post(String body, String... extraHeaders)
            throws IOException, InterruptedException {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://localhost:" + port + McpProtectedResource.MCP_PATH))
                        .header("Authorization", "Bearer " + AUTH_USER_ID)
                        .header("Accept", "application/json, text/event-stream")
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(15))
                        .POST(HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i + 1 < extraHeaders.length; i += 2) {
            request.header(extraHeaders[i], extraHeaders[i + 1]);
        }
        return HttpClient.newHttpClient()
                .send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeToolConfig {

        @Bean
        ProbeTool probeTool() {
            return new ProbeTool();
        }
    }

    /**
     * 임시 도구 — 실행 시점의 스레드·SecurityContext·전송 컨텍스트를 문자열로 돌려준다. stateless 서버라 받을 수 있는 컨텍스트 인자는 {@link
     * McpTransportContext}뿐이다({@code McpSyncServerExchange}는 세션 있는 서버의 것).
     */
    static class ProbeTool {

        @McpTool(name = "probe_context", description = "실행 컨텍스트 조사 (테스트 전용)")
        public String probe(McpTransportContext context) {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            Object transportAuthorization = context.get(McpServerConfig.AUTHORIZATION_CONTEXT_KEY);
            return "securityContextName="
                    + (authentication == null ? "none" : authentication.getName())
                    + ";transportAuthorization="
                    + transportAuthorization
                    + ";thread="
                    + Thread.currentThread().getName();
        }
    }
}
