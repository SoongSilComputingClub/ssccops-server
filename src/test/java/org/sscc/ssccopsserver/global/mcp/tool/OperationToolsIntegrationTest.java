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
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkTypeRepository;
import org.sscc.ssccopsserver.global.audit.AuditLog;
import org.sscc.ssccopsserver.global.logging.EcsJsonEncoder;
import org.sscc.ssccopsserver.global.mcp.McpProtectedResource;
import org.sscc.ssccopsserver.support.SubWorkTypeFixture;
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
    @Autowired private SubWorkTypeRepository subWorkTypeRepository;

    private Long founderId;

    @BeforeAll
    void signUpMembers() throws Exception {
        String founder =
                mockMvc.perform(signup(FOUNDER, body("김도현", "20200001")))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        founderId = JsonPath.parse(founder).read("$.data.memberId", Long.class);
        mockMvc.perform(signup(PLAIN_MEMBER, body("이서연", "20200002")))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("운영 도구 30종이 전부 광고되고 삭제 도구는 없다")
    void advertisesEveryOperationTool() {
        try (McpSyncClient client = connect(FOUNDER)) {
            List<String> names =
                    client.listTools().tools().stream().map(McpSchema.Tool::name).toList();

            assertThat(names)
                    .containsExactlyInAnyOrder(
                            // 1차 (#385)
                            "list_operations",
                            "get_work",
                            "list_sub_works",
                            "get_sub_work",
                            "list_meetings",
                            "get_meeting",
                            "get_me",
                            "transition_sub_work",
                            "check_sub_work_item",
                            // W1 — 업무·하위 업무 (ssccops#365)
                            "list_works",
                            "create_work",
                            "update_work",
                            "create_sub_work",
                            "update_sub_work",
                            "vote_sub_work_approval",
                            "add_sub_work_checklist_item",
                            "update_sub_work_checklist_item_article",
                            // W1 — 회의·승인함·대시보드·유형
                            "create_meeting",
                            "transition_meeting",
                            "list_meeting_agendas",
                            "add_meeting_agenda",
                            "list_approvals",
                            "get_dashboard",
                            "list_sub_work_types",
                            // 콘텐츠 (ssccops#381 · ADR-0038)
                            "create_page",
                            "update_page",
                            "create_post",
                            "update_post",
                            "publish_content",
                            "request_content_image_upload");
            /*
             * 삭제 도구는 소프트 삭제만 열기로 했고(ADR-0037) 그것은 W5에서 낸다 — 지금은
             * 하나도 없어야 한다. 하드 삭제는 어느 파도에서도 열지 않는다.
             */
            assertThat(names).noneMatch(name -> name.startsWith("delete_"));
        }
    }

    /*
     * **부분 수정이 다른 값을 지우지 않는다**(F2 · ssccops#365). 서버 PATCH는 전체 교체라
     * 도구가 상세를 먼저 읽어 빈 필드를 채운다(`WorkPatch.merge`) — 이 테스트가 그 성질을
     * REST 왕복으로 못 박는다. 깨지면 «마감일만 바꿔»가 총평을 지운다.
     */
    @Test
    @DisplayName("update_work — 준 필드만 바뀌고 나머지는 그대로 남는다")
    void updateWorkKeepsFieldsThatWereNotGiven() throws Exception {
        Long workId = createWorkWithReview();

        try (McpSyncClient client = connect(FOUNDER)) {
            McpSchema.CallToolResult result =
                    call(
                            client,
                            "update_work",
                            Map.of("workId", workId, "patch", Map.of("priority", "HIGH")));

            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            String text = text(result);
            assertThat(text).contains("\"priority\":\"HIGH\"");
            // 주지 않은 값이 살아 있다
            assertThat(text).contains("2026 동아리 박람회").contains("총평을 미리 적어 둔다");
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
            // keyword로 좁힌다 — 같은 H2를 쓰는 전이 테스트(#390)가 하위 업무를 하나 만들어 두므로
            McpSchema.CallToolResult result =
                    call(
                            client,
                            "list_sub_works",
                            Map.of("condition", Map.of("size", 5, "keyword", "없는-검색어")));

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

    /*
     * #390 — 도구의 자기 호출은 localhost라 안쪽 요청의 remoteAddr가 127.0.0.1이다. 원 MCP 요청의
     * X-Forwarded-For가 자기 호출에 실려야 감사 로그의 source.ip가 실제 클라이언트가 된다. 감사 줄은
     * AuditPointsTest와 같은 방식(AUDIT 로거의 ListAppender + 실제 인코더)으로 잡는다.
     */
    @Test
    @DisplayName("transition_sub_work — 감사 이벤트의 source.ip는 MCP 요청이 보낸 X-Forwarded-For의 첫 값이다")
    void transitionAuditCarriesClientIp() throws Exception {
        Long subWorkId = createSubWork();
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        Logger auditLogger = (Logger) LoggerFactory.getLogger(AuditLog.LOGGER_NAME);
        EcsJsonEncoder encoder = new EcsJsonEncoder();
        encoder.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        encoder.start();
        captured.start();
        auditLogger.addAppender(captured);
        try (McpSyncClient client = connect(FOUNDER, "203.0.113.7, 10.0.0.1")) {
            McpSchema.CallToolResult result =
                    call(
                            client,
                            "transition_sub_work",
                            Map.of(
                                    "subWorkId",
                                    subWorkId,
                                    "request",
                                    Map.of("transition", "START")));

            assertThat(result.isError()).as(text(result)).isNotEqualTo(Boolean.TRUE);
        } finally {
            auditLogger.detachAppender(captured);
            captured.stop();
        }

        ObjectMapper mapper = new ObjectMapper();
        List<Map<String, Object>> lines =
                captured.list.stream()
                        .map(event -> asJson(mapper, encoder, event))
                        .filter(
                                line ->
                                        "subwork.transition"
                                                .equals(section(line, "event").get("action")))
                        .toList();
        encoder.stop();
        assertThat(lines).as("subwork.transition audit lines").hasSize(1);
        assertThat(section(lines.get(0), "source")).containsEntry("ip", "203.0.113.7");
    }

    private static Map<String, Object> asJson(
            ObjectMapper mapper, EcsJsonEncoder encoder, ILoggingEvent event) {
        try {
            return mapper.readValue(
                    encoder.encode(event), new TypeReference<Map<String, Object>>() {});
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> line, String name) {
        Object value = line.get(name);
        assertThat(value).as(name).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    /* 총평이 든 업무를 만든다 — 부분 수정이 그것을 지우지 않는지 보려면 지워질 값이 있어야 한다 */
    private Long createWorkWithReview() throws Exception {
        String work =
                mockMvc.perform(
                                post("/v1/works")
                                        .header("Authorization", "Bearer " + FOUNDER)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {
                                                  "title": "2026 동아리 박람회",
                                                  "itemType": "EVENT",
                                                  "ownerId": %d,
                                                  "review": "총평을 미리 적어 둔다"
                                                }
                                                """
                                                        .formatted(founderId)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.parse(work).read("$.data.workId", Long.class);
    }

    /* 부모 업무와 하위 업무를 REST로 만든다 — 담당자는 SUPER 본인이라 START 전이가 담당자 판정을 지난다 */
    private Long createSubWork() throws Exception {
        String work =
                mockMvc.perform(
                                post("/v1/works")
                                        .header("Authorization", "Bearer " + FOUNDER)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {
                                                  "title": "2026 동아리 박람회",
                                                  "itemType": "EVENT",
                                                  "ownerId": %d
                                                }
                                                """
                                                        .formatted(founderId)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        Long workId = JsonPath.parse(work).read("$.data.workId", Long.class);
        Long typeId =
                SubWorkTypeFixture.idOf(subWorkTypeRepository, SubWorkTypeFixture.APPROVAL_FREE);
        String subWork =
                mockMvc.perform(
                                post("/v1/sub-works")
                                        .header("Authorization", "Bearer " + FOUNDER)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {
                                                  "workId": %d,
                                                  "title": "부스 배치도 확정",
                                                  "subWorkTypeId": %d,
                                                  "ownerId": %d,
                                                  "dueAt": "2099-01-01T23:59:00+09:00",
                                                  "content": "박람회 부스 위치와 동선을 확정한다"
                                                }
                                                """
                                                        .formatted(workId, typeId, founderId)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.parse(subWork).read("$.data.subWorkId", Long.class);
    }

    private McpSyncClient connect(UUID authUserId) {
        return connect(authUserId, null);
    }

    /* 서버가 stateless(#393)라 initialize는 클라이언트 쪽 의례일 뿐이다 — 세션 id는 오가지 않는다 */
    private McpSyncClient connect(UUID authUserId, String forwardedFor) {
        HttpClientStreamableHttpTransport transport =
                HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                        .endpoint(McpProtectedResource.MCP_PATH)
                        .customizeRequest(
                                builder -> {
                                    builder.header("Authorization", "Bearer " + authUserId);
                                    if (forwardedFor != null) {
                                        builder.header("X-Forwarded-For", forwardedFor);
                                    }
                                })
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
