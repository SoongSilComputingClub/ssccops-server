package org.sscc.ssccopsserver.global.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.sscc.ssccopsserver.SsccopsServerApplication;
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * 재배포 시나리오 (#393 · ssccops#314) — 서버를 내렸다 같은 포트에 다시 올린 뒤 **같은 클라이언트**가 그대로 도구를 부른다.
 *
 * <p>2026-09-13 dev 실측: 세션 있는 Streamable HTTP에서는 Redeploy 뒤 Claude Code가 옛 {@code Mcp-Session-Id}로
 * POST → 404 → «failed to connect»로 굳었다. stateless 전송은 세션을 내지 않으므로 클라이언트가 기억할 것이 없고, 새 프로세스가 같은 요청을
 * 처음 보는 것처럼 처리한다. {@code @SpringBootTest}의 캐시된 컨텍스트로는 «내렸다 올리기»를 흉내 낼 수 없어 여기서만 {@link
 * SpringApplicationBuilder}로 두 번 띄운다 — 두 번째 부팅이 {@code ddl-auto: create}로 스키마를 다시 만드므로 공용 testdb가
 * 아니라 이 클래스만의 H2를 쓴다(다른 컨텍스트가 살아 있는 채로 공용 스키마를 지우면 그쪽이 «Table not found»로 떨어진다).
 */
class McpStatelessRedeployTest {

    private static final String AUTH_USER_ID = UUID.randomUUID().toString();

    @Test
    @DisplayName("서버를 내렸다 같은 포트에 다시 올려도 같은 MCP 클라이언트가 재초기화 없이 도구를 부른다")
    void sameClientSurvivesServerRestart() throws IOException {
        int port = freePort();
        HttpClientStreamableHttpTransport transport =
                HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                        .endpoint(McpProtectedResource.MCP_PATH)
                        .customizeRequest(
                                builder ->
                                        builder.header("Authorization", "Bearer " + AUTH_USER_ID))
                        .build();

        try (McpSyncClient client =
                McpClient.sync(transport).requestTimeout(Duration.ofSeconds(20)).build()) {
            try (ConfigurableApplicationContext first = start(port)) {
                client.initialize();
                assertSignedOut(callGetMe(client));
            }
            // 여기서 첫 프로세스가 죽었다 — 세션이 있었다면 클라이언트가 든 id를 두 번째 프로세스는 모른다
            try (ConfigurableApplicationContext second = start(port)) {
                assertSignedOut(callGetMe(client));
            }
        }
    }

    /*
     * 포트·DB는 run 인자(명령행 프로퍼티)로 준다 — builder.properties(...)는 «기본 프로퍼티»라
     * application.yaml의 server.port=${PORT:8080}에 밀려 8080에 뜬다(실제로 그렇게 떴다).
     */
    private static ConfigurableApplicationContext start(int port) {
        return new SpringApplicationBuilder(
                        SsccopsServerApplication.class, TestJwtDecoderConfig.class)
                .profiles("test")
                .run(
                        "--server.port=" + port,
                        "--spring.datasource.url=jdbc:h2:mem:mcp-redeploy;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
    }

    private static McpSchema.CallToolResult callGetMe(McpSyncClient client) {
        return client.callTool(new McpSchema.CallToolRequest("get_me", Map.of()));
    }

    /* 미가입 토큰이라 signedUp=false — DB에 아무것도 없어도 REST 한 홉이 실제로 돈 증거다 */
    private static void assertSignedOut(McpSchema.CallToolResult result) {
        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertThat(text).contains("\"signedUp\":false");
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
