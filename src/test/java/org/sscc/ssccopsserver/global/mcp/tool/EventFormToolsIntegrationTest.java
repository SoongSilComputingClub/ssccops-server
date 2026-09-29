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
 * 행사·폼 도구 W2 (#494) + 라벨·응답 W4 (#587) — MCP 클라이언트로 붙어 REST를 지나는지.
 * ContentToolsIntegrationTest와 같은 뼈대(첫 가입자 = 최고관리자라 EVENT_MANAGE·FORM_READ·
 * FORM_STATUS_CHANGE·FORM_WRITE·RESPONSE_REVIEW를 다 갖는다).
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

    /** 폼을 만들려면 문항 구성이 필수다 — 최소 한 장·한 문항 */
    private static final String VALID_COMPOSITION =
            """
            {
              "pages": [{"pageTtl": "기본 정보", "pageDescCn": null}],
              "qitems": [{
                "qitemId": "q1", "qitemLblNm": "이름", "qitemTypeCd": "SHORT_TEXT",
                "reqYn": true, "pageSeq": 0, "optionList": []
              }]
            }
            """;

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

    @Test
    @DisplayName("list_form_labels → assign_form_labels 가 REST 를 지나고, 빈 배열이면 전부 해제된다")
    void formLabelAssignThroughRest() throws Exception {
        Long labelId = createLabel("모집");
        Long otherLabelId = createLabel("설문");
        Long formId = createForm("라벨 붙일 폼");

        try (McpSyncClient client = connect(FOUNDER)) {
            McpSchema.CallToolResult labels =
                    call(client, "list_form_labels", Map.of("condition", Map.of("useYn", true)));
            assertThat(labels.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(text(labels))
                    .contains("\"formLblId\":" + labelId)
                    .contains("\"lblNm\":\"모집\"");

            McpSchema.CallToolResult assigned =
                    call(
                            client,
                            "assign_form_labels",
                            Map.of(
                                    "formId",
                                    formId,
                                    "request",
                                    Map.of("labelIds", java.util.List.of(labelId, otherLabelId))));
            assertThat(assigned.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(text(assigned))
                    .contains("\"formLblId\":" + labelId)
                    .contains("\"formLblId\":" + otherLabelId);

            // 전체 교체다 — 하나만 보내면 나머지는 해제된다
            McpSchema.CallToolResult replaced =
                    call(
                            client,
                            "assign_form_labels",
                            Map.of(
                                    "formId",
                                    formId,
                                    "request",
                                    Map.of("labelIds", java.util.List.of(labelId))));
            assertThat(replaced.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(text(replaced))
                    .contains("\"formLblId\":" + labelId)
                    .doesNotContain("\"formLblId\":" + otherLabelId);
        }
    }

    @Test
    @DisplayName("list_form_responses 는 응답이 없어도 성공하고, 없는 응답 심사는 404 도구 오류다")
    void formResponseToolsThroughRest() throws Exception {
        Long formId = createForm("응답 없는 폼");

        try (McpSyncClient client = connect(FOUNDER)) {
            McpSchema.CallToolResult listed =
                    call(client, "list_form_responses", Map.of("formId", formId));
            assertThat(listed.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(text(listed)).doesNotContain("\"success\"");

            /*
             * 없는 응답 id 는 404 다. 다른 폼의 응답 id 도 같은 코드로 오므로(그 폼에 그 번호가
             * 있는지조차 알려주지 않는다) 모델이 받는 것은 어느 쪽이든 같다.
             */
            McpSchema.CallToolResult missing =
                    call(
                            client,
                            "review_form_response",
                            Map.of(
                                    "formId",
                                    formId,
                                    "formRspnsId",
                                    999_999,
                                    "request",
                                    Map.of("rspnsSttsCd", "ACCEPTED")));
            assertThat(missing.isError()).isTrue();
            assertThat(text(missing)).contains("FORM_RESPONSE_NOT_FOUND");
        }
    }

    /*
     * 응답 **상세** 도구가 없다는 것이 #587 의 결정이다 — rspnsCn 은 문항 id 를 키로 한 맵이라
     * 이름 기반 마스킹(ToolOutputRedactor)이 안에 닿지 않고, 문항이 연락처·주소를 물으면 그 값이
     * 그대로 나간다. 이름만 보고 «빠뜨렸네» 하며 더하는 것을 막기 위해 목록으로 못 박는다.
     */
    @Test
    @DisplayName("응답 상세를 내주는 도구는 없다 — 목록·심사만 있다 (#587 결정)")
    void noFormResponseDetailTool() {
        try (McpSyncClient client = connect(FOUNDER)) {
            java.util.List<String> names =
                    client.listTools().tools().stream().map(McpSchema.Tool::name).toList();

            assertThat(names).contains("list_form_responses", "review_form_response");
            assertThat(names)
                    .noneSatisfy(
                            name ->
                                    assertThat(name)
                                            .as("응답 상세를 여는 도구는 결정으로 막혀 있다 (#587)")
                                            .isEqualTo("get_form_response"));
        }
    }

    private Long createLabel(String name) throws Exception {
        String response =
                mockMvc.perform(
                                post("/v1/form-labels")
                                        .header("Authorization", "Bearer " + FOUNDER)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"lblNm\": \"%s\"}".formatted(name)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.parse(response).read("$.data.formLblId", Long.class);
    }

    private Long createForm(String title) throws Exception {
        String body =
                """
                {
                  "formTtlNm": "%s",
                  "formSttsCd": "DRAFT",
                  "qitemCpstCn": %s
                }
                """
                        .formatted(title, VALID_COMPOSITION);
        String response =
                mockMvc.perform(
                                post("/v1/forms")
                                        .header("Authorization", "Bearer " + FOUNDER)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.parse(response).read("$.data.formId", Long.class);
    }

    /*
     * **ADR-0053 이 여는 것과 닫는 것이 한 시험에 있다.** 삭제 뒤 조회가 404 가 되고 되살리면
     * 지우기 직전 모습으로 돌아오는 것이 그 ADR 의 근거(«되살릴 수 있다»)가 실제로 성립한다는
     * 증거다 — 이것이 깨지면 폼 삭제 도구를 여는 이유가 사라진다.
     */
    @Test
    @DisplayName("delete_form → get_form 404 → restore_form 이 지우기 직전 모습으로 되살린다")
    void formDeleteAndRestoreThroughRest() throws Exception {
        Long formId = createForm("지웠다 되살릴 폼");

        try (McpSyncClient client = connect(FOUNDER)) {
            McpSchema.CallToolResult deleted =
                    call(client, "delete_form", Map.of("formId", formId));
            assertThat(deleted.isError()).isNotEqualTo(Boolean.TRUE);

            // 지운 폼은 없는 폼과 같은 404 다 — 공개 링크가 존재 여부를 알려주지 않기 위해서다
            McpSchema.CallToolResult gone = call(client, "get_form", Map.of("formId", formId));
            assertThat(gone.isError()).isTrue();
            assertThat(text(gone)).contains("NOT_FOUND");

            // 이미 지운 폼을 또 지우면 409 — 재시도해도 같다
            McpSchema.CallToolResult twice = call(client, "delete_form", Map.of("formId", formId));
            assertThat(twice.isError()).isTrue();
            assertThat(text(twice)).contains("ALREADY_DELETED");

            McpSchema.CallToolResult restored =
                    call(client, "restore_form", Map.of("formId", formId));
            assertThat(restored.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(text(restored)).contains("\"formTtlNm\":\"지웠다 되살릴 폼\"");

            // 지워지지 않은 폼을 되살리면 409
            McpSchema.CallToolResult notDeleted =
                    call(client, "restore_form", Map.of("formId", formId));
            assertThat(notDeleted.isError()).isTrue();
            assertThat(text(notDeleted)).contains("NOT_DELETED");
        }
    }

    @Test
    @DisplayName("delete_event → restore_event — 게시 상태까지 그대로 돌아온다")
    void eventDeleteAndRestoreThroughRest() {
        try (McpSyncClient client = connect(FOUNDER)) {
            McpSchema.CallToolResult created =
                    call(
                            client,
                            "create_event",
                            Map.of(
                                    "request",
                                    Map.of(
                                            "eventClsfCd", "RECRUIT",
                                            "eventTtl", "지웠다 되살릴 행사",
                                            "mtxtCn", "# 본문",
                                            "plcNm", "학생회관")));
            assertThat(created.isError()).isNotEqualTo(Boolean.TRUE);
            Long eventId = JsonPath.parse(text(created)).read("$.eventId", Long.class);

            call(
                    client,
                    "change_event_status",
                    Map.of("eventId", eventId, "request", Map.of("action", "PUBLISH")));

            McpSchema.CallToolResult deleted =
                    call(client, "delete_event", Map.of("eventId", eventId));
            assertThat(deleted.isError()).isNotEqualTo(Boolean.TRUE);

            McpSchema.CallToolResult gone = call(client, "get_event", Map.of("eventId", eventId));
            assertThat(gone.isError()).isTrue();

            McpSchema.CallToolResult restored =
                    call(client, "restore_event", Map.of("eventId", eventId));
            assertThat(restored.isError()).isNotEqualTo(Boolean.TRUE);
            // 게시 상태는 지울 때 그대로 남으므로 게시 중이던 행사는 다시 게시 중이다
            assertThat(text(restored))
                    .contains("\"eventTtl\":\"지웠다 되살릴 행사\"")
                    .contains("\"eventSttsCd\":\"PUBLISHED\"");
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
