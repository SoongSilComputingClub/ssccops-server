package org.sscc.ssccopsserver.global.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
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

import com.jayway.jsonpath.JsonPath;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;

/*
 * 콘텐츠 도구의 REST 왕복 (ssccops#381). OperationToolsIntegrationTest와 같은 배치 — 실제 포트 +
 * MCP 클라이언트 · 이 클래스만의 H2 · 가입 API로 만든 최초 가입자(SUPER → CONTENT_MANAGE 포함).
 * create_page → update_page(읽고-합치기) → publish_content가 REST 층의 인가·검증·이력을 그대로
 * 지나는지와, 역할 없는 회원이 «권한 없음»으로 끝나는지를 본다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties =
                "spring.datasource.url="
                        + "jdbc:h2:mem:mcp-content-tools;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContentToolsIntegrationTest {

    private static final UUID FOUNDER = UUID.randomUUID();
    private static final UUID PLAIN_MEMBER = UUID.randomUUID();

    @LocalServerPort private int port;
    @Autowired private MockMvc mockMvc;

    @BeforeAll
    void signUpMembers() throws Exception {
        mockMvc.perform(signup(FOUNDER, body("김도현", "20200011"))).andExpect(status().isCreated());
        mockMvc.perform(signup(PLAIN_MEMBER, body("이서연", "20200012")))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("create_page → update_page(바꿀 것만) → publish_content가 REST를 지나 게시된다")
    void pageRoundTripThroughRest() {
        try (McpSyncClient client = connect(FOUNDER)) {
            McpSchema.CallToolResult created =
                    call(
                            client,
                            "create_page",
                            Map.of(
                                    "request",
                                    Map.of("slug", "about", "ttl", "소개", "mtxt", "# 소개")));
            assertThat(created.isError()).isNotEqualTo(Boolean.TRUE);
            String createdText = text(created);
            assertThat(createdText)
                    .contains("\"pubSttsCd\":\"DRAFT\"")
                    .contains("\"slug\":\"about\"");
            // ApiResponse 봉투가 아니다
            assertThat(createdText).doesNotContain("\"success\"");
            Long pageId = JsonPath.parse(createdText).read("$.pageId", Long.class);

            // 제목만 줘도 본문·slug가 남는다 — 읽고-합치기
            McpSchema.CallToolResult updated =
                    call(
                            client,
                            "update_page",
                            Map.of("pageId", pageId, "patch", Map.of("ttl", "동아리 소개")));
            assertThat(updated.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(text(updated))
                    .contains("\"ttl\":\"동아리 소개\"")
                    .contains("\"mtxt\":\"# 소개\"")
                    .contains("\"slug\":\"about\"");

            McpSchema.CallToolResult published =
                    call(
                            client,
                            "publish_content",
                            Map.of("kind", "page", "id", pageId, "publish", true));
            assertThat(published.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(text(published)).contains("\"pubSttsCd\":\"PUBLISHED\"");

            // 같은 상태로 다시 게시하면 서버의 409가 도구 오류 문장으로 온다
            McpSchema.CallToolResult again =
                    call(
                            client,
                            "publish_content",
                            Map.of("kind", "page", "id", pageId, "publish", true));
            assertThat(again.isError()).isTrue();
            assertThat(text(again)).contains("CONTENT_ALREADY_PUBLISHED");
        }
    }

    @Test
    @DisplayName("publish_content의 kind가 page·post가 아니면 REST를 부르지 않고 거절한다")
    void publishContentRejectsUnknownKind() {
        try (McpSyncClient client = connect(FOUNDER)) {
            McpSchema.CallToolResult result =
                    call(
                            client,
                            "publish_content",
                            Map.of("kind", "event", "id", 1, "publish", true));
            assertThat(result.isError()).isTrue();
            assertThat(text(result)).contains("page 또는 post");
        }
    }

    @Test
    @DisplayName("역할 없는 회원의 create_post는 «권한 없음»으로 끝난다 — 재시도해도 같다")
    void plainMemberIsForbidden() {
        try (McpSyncClient client = connect(PLAIN_MEMBER)) {
            McpSchema.CallToolResult result =
                    call(
                            client,
                            "create_post",
                            Map.of(
                                    "request",
                                    Map.of(
                                            "slug", "news-1",
                                            "cntntClsfCd", "NEWS",
                                            "ttl", "소식",
                                            "mtxt", "# 소식",
                                            "actvYmd", "2026-09-19")));
            assertThat(result.isError()).isTrue();
            assertThat(text(result)).contains("권한이 없습니다").contains("재시도해도");
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
