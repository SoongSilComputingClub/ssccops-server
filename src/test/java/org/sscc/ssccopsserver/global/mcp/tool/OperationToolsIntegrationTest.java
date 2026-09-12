package org.sscc.ssccopsserver.global.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.sscc.ssccopsserver.global.mcp.McpProtectedResource;
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * 도구가 실제로 자기 REST를 부른다 (#385 · ADR-0027) — 실제 포트 + MCP Java 클라이언트로 `tools/call`.
 *
 * <p>세 신원으로 부른다: 최초 가입자(SUPER · #71 부트스트랩) · 역할 없는 두 번째 가입자 · 미가입 토큰. 인가·미가입 차단이 REST 계층에서 그대로 도는지가
 * 이 테스트의 요점이라, 서비스를 흉내 내지 않고 가입 API로 상태를 만든다. 가입이 커밋되므로 이 클래스만의 H2를 쓴다 (부트스트랩 테스트와 같은 이유).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties =
                "spring.datasource.url="
                    + "jdbc:h2:mem:mcp-operation-tools;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OperationToolsIntegrationTest {

    private static final UUID FOUNDER = UUID.randomUUID();
    private static final UUID PLAIN_MEMBER = UUID.randomUUID();
    private static final UUID STRANGER = UUID.randomUUID();

    @LocalServerPort private int port;
    @Autowired private MockMvc mockMvc;

    @BeforeAll
    void signUpMembers() throws Exception {
        mockMvc.perform(signup(FOUNDER, body("김도현", "20200001"))).andExpect(status().isCreated());
        mockMvc.perform(signup(PLAIN_MEMBER, body("이서연", "20200002")))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("도구 9종이 전부 광고되고 update 도구는 없다")
    void advertisesNineTools() {
        try (McpSyncClient client = connect(FOUNDER)) {
            List<String> names =
                    client.listTools().tools().stream().map(McpSchema.Tool::name).toList();

            assertThat(names)
                    .containsExactlyInAnyOrder(
                            "list_operations",
                            "get_work",
                            "list_sub_works",
                            "get_sub_work",
                            "list_meetings",
                            "get_meeting",
                            "get_me",
                            "transition_sub_work",
                            "check_sub_work_item");
            assertThat(names).noneMatch(name -> name.startsWith("update_"));
        }
    }

    @Test
    @DisplayName("get_me — 봉투를 벗긴 세션이 오고 연락처·이메일·학번은 없다")
    void getMeUnwrapsAndRedacts() {
        try (McpSyncClient client = connect(FOUNDER)) {
            McpSchema.CallToolResult result = call(client, "get_me", Map.of());

            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            String text = text(result);
            assertThat(text).contains("\"signedUp\":true").contains("김도현");
            assertThat(text).contains("최고관리자");
            // 값이 없다 — 키는 null로 남을 수 있다(mcp-annotations의 JSON 직렬화는 null을 뺀 채 내지 않는다)
            assertThat(text).doesNotContain("010-1234-5678", "20200001");
            assertThat(text)
                    .doesNotContain("\"phoneNumber\":\"", "\"email\":\"", "\"studentNumber\":\"");
            // ApiResponse 봉투가 아니다
            assertThat(text).doesNotContain("\"success\"");
            // AuthUserResponse.email도 걷혔다(이메일은 어느 자리에 있든 나가지 않는다)
            assertThat(text).doesNotContain("@sscc.org");
        }
    }

    @Test
    @DisplayName("list_sub_works — SUPER는 200(빈 목록), 역할 없는 회원은 «권한 없음»으로 끝난다")
    void listSubWorksHonoursAuthorization() {
        try (McpSyncClient client = connect(FOUNDER)) {
            McpSchema.CallToolResult result =
                    call(client, "list_sub_works", Map.of("condition", Map.of("size", 5)));

            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(text(result)).contains("\"items\":[]").contains("\"hasMore\":false");
        }
        try (McpSyncClient client = connect(PLAIN_MEMBER)) {
            McpSchema.CallToolResult result = call(client, "list_sub_works", Map.of());

            assertThat(result.isError()).isTrue();
            assertThat(text(result)).contains("권한이 없습니다").contains("재시도해도");
        }
    }

    @Test
    @DisplayName("미가입 토큰 — get_me는 signedUp=false, 다른 도구는 «가입 필요»로 끝난다")
    void strangerIsToldToSignUp() {
        try (McpSyncClient client = connect(STRANGER)) {
            McpSchema.CallToolResult me = call(client, "get_me", Map.of());
            assertThat(me.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(text(me)).contains("\"signedUp\":false");

            McpSchema.CallToolResult list = call(client, "list_sub_works", Map.of());
            assertThat(list.isError()).isTrue();
            assertThat(text(list)).contains("가입이 필요합니다");
        }
    }

    @Test
    @DisplayName("없는 하위 업무 — REST의 404 code·message가 도구 오류로 그대로 온다")
    void notFoundCarriesRestCode() {
        try (McpSyncClient client = connect(FOUNDER)) {
            McpSchema.CallToolResult result =
                    call(client, "get_sub_work", Map.of("subWorkId", 999999));

            assertThat(result.isError()).isTrue();
            assertThat(text(result)).startsWith("[");
        }
    }

    private McpSyncClient connect(UUID authUserId) {
        HttpClientStreamableHttpTransport transport =
                HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                        .endpoint(McpProtectedResource.MCP_PATH)
                        .customizeRequest(
                                builder -> builder.header("Authorization", "Bearer " + authUserId))
                        .build();
        McpSyncClient client =
                McpClient.sync(transport).requestTimeout(Duration.ofSeconds(20)).build();
        client.initialize();
        return client;
    }

    private static McpSchema.CallToolResult call(
            McpSyncClient client, String tool, Map<String, Object> arguments) {
        return client.callTool(new McpSchema.CallToolRequest(tool, arguments));
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            signup(UUID authUserId, String body) {
        return post("/v1/members/signup")
                .header("Authorization", "Bearer " + authUserId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static String body(String name, String studentNumber) {
        return """
                {
                  "name": "%s",
                  "phoneNumber": "010-1234-5678",
                  "memberStatusCode": "ENROLLED",
                  "studentNumber": "%s",
                  "departmentName": "컴퓨터학부",
                  "academicYear": 3
                }
                """
                .formatted(name, studentNumber);
    }
}
