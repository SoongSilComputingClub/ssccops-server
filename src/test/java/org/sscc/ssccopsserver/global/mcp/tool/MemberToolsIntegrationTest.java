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

import com.jayway.jsonpath.JsonPath;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;

/*
 * 회원 도구 W5 (#589 · ADR-0037) — MCP 클라이언트로 붙어 REST 를 지나는지.
 *
 * **이 클래스가 지키는 것은 마스킹이다.** ADR-0037 이 «이름은 싣고 연락처·이메일·학번은 지운다»로
 * 회원 도구를 열었으므로, 그 규칙이 실제 왕복에서 성립하는지를 한 번은 실물로 봐야 한다 —
 * `ToolOutputRedactorTest` 는 트리를, `ToolOutputRedactorCoverageTest` 는 DTO 목록을 보지만
 * «도구를 불렀을 때 그 값이 정말 안 나오는가»는 여기서만 답이 나온다.
 *
 * 뼈대는 다른 도구 통합 시험과 같다 — 첫 가입자가 최고관리자라 MEMBER_MANAGE·ROLE_MANAGE 를
 * 함께 갖는다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties =
                "spring.datasource.url="
                        + "jdbc:h2:mem:mcp-member-tools;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemberToolsIntegrationTest {

    private static final UUID FOUNDER = UUID.randomUUID();
    private static final UUID SECOND = UUID.randomUUID();

    @LocalServerPort private int port;
    @Autowired private MockMvc mockMvc;

    private Long secondMemberId;

    @BeforeAll
    void signUp() throws Exception {
        mockMvc.perform(signup(FOUNDER, body("김도현", "20200021", "010-1111-2222")))
                .andExpect(status().isCreated());
        String created =
                mockMvc.perform(signup(SECOND, body("이서연", "20200022", "010-3333-4444")))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        secondMemberId = JsonPath.parse(created).read("$.data.memberId", Long.class);
    }

    /*
     * **이 시험이 이 클래스의 이유다.** 목록·상세 둘 다 이름은 내고 연락처·학번은 내지 않아야
     * 한다. 걷힌 필드는 키가 남고 값이 null 이므로(mcp-annotations 직렬화가 null 을 빼지 않는다)
     * «키가 없다»가 아니라 «그 값이 없다»로 본다 — 값으로 보지 않으면 키를 지우는 변경과
     * 마스킹을 걷는 변경을 구별하지 못한다.
     */
    @Test
    @DisplayName("list_members · get_member — 이름은 나오고 연락처·학번은 지워진다 (ADR-0037)")
    void memberToolsRedactContactAndStudentNumber() {
        try (McpSyncClient client = connect(FOUNDER)) {
            McpSchema.CallToolResult listed = call(client, "list_members", Map.of());
            assertThat(listed.isError()).isNotEqualTo(Boolean.TRUE);
            String listText = text(listed);
            assertThat(listText).contains("\"name\":\"김도현\"").contains("\"name\":\"이서연\"");
            assertThat(listText)
                    .doesNotContain("010-1111-2222")
                    .doesNotContain("010-3333-4444")
                    .doesNotContain("20200021")
                    .doesNotContain("20200022");

            McpSchema.CallToolResult detail =
                    call(client, "get_member", Map.of("memberId", secondMemberId));
            assertThat(detail.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(text(detail))
                    .contains("\"name\":\"이서연\"")
                    .doesNotContain("010-3333-4444")
                    .doesNotContain("20200022");
        }
    }

    @Test
    @DisplayName("list_members — q 로 이름을 걸러 받는다")
    void listMembersFiltersByQuery() {
        try (McpSyncClient client = connect(FOUNDER)) {
            McpSchema.CallToolResult listed =
                    call(client, "list_members", Map.of("condition", Map.of("q", "이서연")));
            assertThat(listed.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(text(listed)).contains("이서연").doesNotContain("김도현");
        }
    }

    /*
     * 등급 변경 → 이력이 그 줄을 든다. 같은 등급으로 다시 바꾸는 것이 400 NO_CHANGE 인 것도
     * 함께 본다 — 도구 설명이 그것을 적어 두었고, 모델이 그 400 을 받고 되풀이하지 않게 하는
     * 근거가 실제 응답이어야 한다.
     */
    @Test
    @DisplayName("change_member_grade → list_member_histories 에 그 줄이 남고, 같은 등급 재지정은 400")
    void gradeChangeLeavesHistoryAndRejectsNoChange() {
        try (McpSyncClient client = connect(FOUNDER)) {
            McpSchema.CallToolResult changed =
                    call(
                            client,
                            "change_member_grade",
                            Map.of(
                                    "memberId",
                                    secondMemberId,
                                    "request",
                                    Map.of("aftrMbrGrdCd", "FULL", "grdChgRsnCn", "정회원 승격")));
            assertThat(changed.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(text(changed)).contains("\"membershipGradeCode\":\"FULL\"");

            McpSchema.CallToolResult histories =
                    call(
                            client,
                            "list_member_histories",
                            Map.of(
                                    "memberId",
                                    secondMemberId,
                                    "condition",
                                    Map.of("type", List.of("GRADE"))));
            assertThat(histories.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(text(histories)).contains("\"newCode\":\"FULL\"").contains("정회원 승격");

            McpSchema.CallToolResult again =
                    call(
                            client,
                            "change_member_grade",
                            Map.of(
                                    "memberId",
                                    secondMemberId,
                                    "request",
                                    Map.of("aftrMbrGrdCd", "FULL")));
            assertThat(again.isError()).isTrue();
            assertThat(text(again)).contains("NO_CHANGE");
        }
    }

    /*
     * 역할은 부여 → 목록 → 종료가 한 줄기다. **종료가 행을 지우지 않는다**는 것이 요점이라
     * 종료 뒤에도 목록에 남는지를 본다 — 지우는 구현으로 바뀌면 여기서 깨진다.
     */
    @Test
    @DisplayName("list_roles → assign_member_role → update_member_role_assignment — 종료해도 행이 남는다")
    void roleAssignmentRoundTrip() {
        try (McpSyncClient client = connect(FOUNDER)) {
            McpSchema.CallToolResult roles = call(client, "list_roles", Map.of());
            assertThat(roles.isError()).isNotEqualTo(Boolean.TRUE);
            Long roleId = JsonPath.parse(text(roles)).read("$[0].roleId", Long.class);

            McpSchema.CallToolResult assigned =
                    call(
                            client,
                            "assign_member_role",
                            Map.of(
                                    "memberId",
                                    secondMemberId,
                                    "request",
                                    Map.of("roleId", roleId)));
            assertThat(assigned.isError()).isNotEqualTo(Boolean.TRUE);
            String assignedText = text(assigned);
            assertThat(assignedText).contains("\"current\":true");
            Long mbrRoleId = JsonPath.parse(assignedText).read("$.mbrRoleId", Long.class);

            // 같은 역할을 기간이 겹쳐 다시 부여하면 409 — 도구 설명이 적은 그대로다
            McpSchema.CallToolResult duplicated =
                    call(
                            client,
                            "assign_member_role",
                            Map.of(
                                    "memberId",
                                    secondMemberId,
                                    "request",
                                    Map.of("roleId", roleId)));
            assertThat(duplicated.isError()).isTrue();
            assertThat(text(duplicated)).contains("ROLE_ALREADY_ASSIGNED");

            McpSchema.CallToolResult ended =
                    call(
                            client,
                            "update_member_role_assignment",
                            Map.of(
                                    "memberId",
                                    secondMemberId,
                                    "mbrRoleId",
                                    mbrRoleId,
                                    "request",
                                    // 시작일이 오늘(부여 기본값)이라 종료일은 그보다 뒤여야 한다 —
                                    // 이르면 400 VALIDATION_FAILED 다
                                    Map.of("roleEndYmd", "2026-12-31")));
            assertThat(ended.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(text(ended)).contains("\"roleEndYmd\":\"2026-12-31\"");

            // 종료는 삭제가 아니다 — current 를 주지 않으면 지난 임기도 그대로 온다
            McpSchema.CallToolResult after =
                    call(client, "list_member_roles", Map.of("memberId", secondMemberId));
            assertThat(after.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(text(after)).contains("\"mbrRoleId\":" + mbrRoleId);
        }
    }

    /*
     * 상태 변경은 warnings 가 요점이다 — 탈퇴·제명으로 바꿔도 서버가 역할·담당 업무를 정리하지
     * 않으므로 도구가 그 배열을 그대로 내려 모델이 사람에게 알릴 수 있어야 한다.
     */
    @Test
    @DisplayName("change_member_status — warnings 자리가 응답에 있다")
    void statusChangeCarriesWarnings() {
        try (McpSyncClient client = connect(FOUNDER)) {
            McpSchema.CallToolResult changed =
                    call(
                            client,
                            "change_member_status",
                            Map.of(
                                    "memberId",
                                    secondMemberId,
                                    "request",
                                    Map.of("aftrMbrSttsCd", "WITHDRAWN", "sttsChgRsnCn", "본인 요청")));
            assertThat(changed.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(text(changed))
                    .contains("\"membershipStatusCode\":\"WITHDRAWN\"")
                    .contains("\"warnings\"");
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

    private static String body(String name, String studentNumber, String phoneNumber) {
        return """
                {
                  "name": "%s",
                  "phoneNumber": "%s",
                  "memberStatusCode": "ENROLLED",
                  "studentNumber": "%s",
                  "departmentName": "컴퓨터학부",
                  "academicYear": 3
                }
                """
                .formatted(name, phoneNumber, studentNumber);
    }
}
