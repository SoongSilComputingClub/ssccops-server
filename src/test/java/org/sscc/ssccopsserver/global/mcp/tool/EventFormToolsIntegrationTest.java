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
 * 행사·폼 도구 W2 (#494) — MCP 클라이언트로 붙어 REST를 지나는지. ContentToolsIntegrationTest와 같은
 * 뼈대(첫 가입자 = 최고관리자라 EVENT_MANAGE·FORM_READ·FORM_STATUS_CHANGE를 다 갖는다).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties =
                "spring.datasource.url="
                    + "jdbc:h2:mem:mcp-event-form-tools;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventFormToolsIntegrationTest {

    private static final UUID FOUNDER = UUID.randomUUID();

    @LocalServerPort private int port;
    @Autowired private MockMvc mockMvc;

    @BeforeAll
    void signUpFounder() throws Exception {
        mockMvc.perform(signup(FOUNDER, body("김도현", "20200021"))).andExpect(status().isCreated());
    }

    @Test
    @DisplayName(
            "create_event → update_event(제목만) → change_event_status(PUBLISH) → list_events가 REST를"
                    + " 지난다")
    void eventRoundTrip() {
        try (McpSyncClient client = connect(FOUNDER)) {
            McpSchema.CallToolResult created =
                    call(
                            client,
                            "create_event",
                            Map.of(
                                    "request",
                                    Map.of(
                                            "eventClsfCd", "RECRUIT",
                                            "eventTtl", "가을 MT",
                                            "mtxtCn", "# 가을 MT",
                                            "plcNm", "강촌")));
            assertThat(created.isError()).isNotEqualTo(Boolean.TRUE);
            String createdText = text(created);
            assertThat(createdText)
                    .contains("\"eventSttsCd\":\"DRAFT\"")
                    .doesNotContain("\"success\"");
            Long eventId = JsonPath.parse(createdText).read("$.eventId", Long.class);

            McpSchema.CallToolResult updated =
                    call(
                            client,
                            "update_event",
                            Map.of("eventId", eventId, "patch", Map.of("eventTtl", "2026 가을 MT")));
            assertThat(updated.isError()).isNotEqualTo(Boolean.TRUE);
            // 제목만 줘도 본문·장소가 남는다 — 읽고-합치기(PUT)
            assertThat(text(updated))
                    .contains("\"eventTtl\":\"2026 가을 MT\"")
                    .contains("\"mtxtCn\":\"# 가을 MT\"")
                    .contains("\"plcNm\":\"강촌\"");

            McpSchema.CallToolResult published =
                    call(
                            client,
                            "change_event_status",
                            Map.of("eventId", eventId, "request", Map.of("action", "PUBLISH")));
            assertThat(published.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(text(published)).contains("\"eventSttsCd\":\"PUBLISHED\"");

            McpSchema.CallToolResult listed =
                    call(
                            client,
                            "list_events",
                            Map.of("condition", Map.of("eventSttsCd", "PUBLISHED")));
            assertThat(listed.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(text(listed)).contains("\"eventId\":" + eventId);
        }
    }

    @Test
    @DisplayName("list_forms(다중 상태) · get_form 없는 id는 404가 도구 오류로 온다")
    void formToolsReadThroughRest() {
        try (McpSyncClient client = connect(FOUNDER)) {
            McpSchema.CallToolResult listed =
                    call(
                            client,
                            "list_forms",
                            Map.of(
                                    "condition",
                                    Map.of(
                                            "receiptStatuses",
                                            java.util.List.of("DRAFT", "ACCEPTING"))));
            assertThat(listed.isError()).isNotEqualTo(Boolean.TRUE);

            McpSchema.CallToolResult missing = call(client, "get_form", Map.of("formId", 999_999));
            assertThat(missing.isError()).isTrue();
            assertThat(text(missing)).contains("NOT_FOUND");
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
