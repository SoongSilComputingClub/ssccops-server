package org.sscc.ssccopsserver.global.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;

/*
 * 도구 입력 스키마의 `required` 가 **서버가 실제로 강제하는 것**과 같은가 (#591 · ssccops#365).
 *
 * `McpToolInputSchemaCorrector` 가 하는 일을 **밖에서 실제 MCP 클라이언트로** 확인한다 — 그 클래스의
 * 단위 테스트가 아니라 «클라이언트가 받는 스키마»를 보는 것이 요점이다. 스키마 검증은 서버가 아니라
 * **클라이언트가** 하므로, 여기가 맞아야 도구를 부를 수 있다.
 *
 * 깨지면 둘 중 하나다: ① 라이브러리가 스키마 모양·기본값을 바꿨다(그러면 corrector 가 아직 필요한지
 * 부터 본다) ② 누군가 입력 record 에 필드를 더했는데 필수 여부가 서버와 갈렸다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties =
                "spring.datasource.url="
                    + "jdbc:h2:mem:mcp-schema-contract;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpToolSchemaContractTest {

    @LocalServerPort private int port;

    /*
     * 신고된 자리 (#591). 그때는 안건명과 연결 운영 건이 «둘 중 하나»인데 스키마가 둘 다 요구해
     * **부를 수 있는 방법이 없었다.** 그 뒤 ADR-0055 로 **안건은 언제나 운영 건을 가리키게** 바뀌어
     * (#593) 이제 필수는 `targetOperationId` 하나다.
     *
     * 이 단정이 두 번 바뀐 것이 이 테스트의 값을 말한다 — 스키마가 **서버 계약에서 파생**되므로
     * 계약이 바뀌면 여기가 먼저 깨진다. `@NotNull` 을 떼면 optional 로 따라 내려간다.
     */
    @Test
    @DisplayName("add_meeting_agenda — 필수는 targetOperationId 하나다 (ADR-0055)")
    void agendaItemRequiresItsOperation() {
        assertThat(nestedRequired("add_meeting_agenda", "request"))
                .containsExactly("targetOperationId");
    }

    /*
     * 안건 수정은 «전체 교체»라 읽고-합치기 도구와 다르다 (#599). 내용 둘은 생략하면 비우는 것이
     * **뜻이 있는 값**이므로 optional 이 맞고, 처리 구분만 `@NotNull` 이다 — 화면이 칩 중 하나를
     * 늘 골라 두기 때문이다(MeetingAgendaUpdateRequest 주석). 셋 다 필수로 굳으면 «결과만 적는다»가
     * 안 되고, 셋 다 optional 이 되면 서버가 400 을 내는 호출이 스키마를 통과한다.
     */
    @Test
    @DisplayName("update_meeting_agenda — 필수는 processStatus 하나다 (전체 교체)")
    void agendaUpdateRequiresOnlyItsProcessStatus() {
        assertThat(nestedRequired("update_meeting_agenda", "request"))
                .containsExactly("processStatus");
    }

    @Test
    @DisplayName("읽고-합치기 도구의 patch 에는 필수 필드가 없다 — «바꿀 것만 준다»")
    void patchToolsRequireNothing() {
        assertThat(nestedRequired("update_work", "patch")).isEmpty();
        assertThat(nestedRequired("update_sub_work", "patch")).isEmpty();
        assertThat(nestedRequired("update_event", "patch")).isEmpty();
        assertThat(nestedRequired("update_page", "patch")).isEmpty();
        assertThat(nestedRequired("update_post", "patch")).isEmpty();
    }

    @Test
    @DisplayName("검색 조건에는 필수 필드가 없다 — 필터 하나만 걸 수 있어야 한다")
    void searchConditionsRequireNothing() {
        assertThat(nestedRequired("list_sub_works", "condition")).isEmpty();
        assertThat(nestedRequired("list_works", "condition")).isEmpty();
        assertThat(nestedRequired("list_members", "condition")).isEmpty();
        assertThat(nestedRequired("list_forms", "condition")).isEmpty();
        assertThat(nestedRequired("list_events", "condition")).isEmpty();
        assertThat(nestedRequired("list_academic_programs", "condition")).isEmpty();
    }

    /*
     * 반대쪽 — 서버가 진짜 요구하는 것은 **여전히 required 여야 한다.** required 를 통째로 비우는
     * 수정이었다면 이 테스트가 통과하지 못한다.
     */
    @Test
    @DisplayName("서버가 @NotNull·@NotBlank 로 요구하는 것은 required 로 남는다")
    void serverRequiredFieldsStayRequired() {
        assertThat(nestedRequired("create_event", "request"))
                .containsExactlyInAnyOrder("eventClsfCd", "eventTtl", "mtxtCn");
        assertThat(nestedRequired("create_page", "request"))
                .containsExactlyInAnyOrder("slug", "ttl", "mtxt");
        assertThat(nestedRequired("transition_sub_work", "request")).contains("transition");
        assertThat(nestedRequired("change_member_grade", "request")).contains("aftrMbrGrdCd");
        // 지정은 전체 교체라 빈 배열은 «전부 해제»이고 필드 누락은 서버가 400이다 (#624)
        assertThat(nestedRequired("assign_work_tags", "request")).containsExactly("tagIds");
        assertThat(nestedRequired("assign_form_labels", "request")).containsExactly("labelIds");
    }

    /** 배열 요소의 record 도 같은 규칙을 받는다 — 거기까지 내려가지 않으면 목록 도구가 그대로 막힌다. */
    @Test
    @DisplayName("배열 요소 안의 required 도 서버 계약을 따른다")
    void arrayElementsFollowTheContract() {
        Map<String, Object> request = property("select_academic_recruitment", "request");
        assertThat(required(request)).containsExactly("selections");
        Map<String, Object> selections = child(request, "selections");
        assertThat(required(itemsOf(selections)))
                .containsExactlyInAnyOrder("formRspnsId", "ptcpSttsCd");
    }

    /*
     * 상위 인자는 corrector 가 손대지 않는다 — `@McpToolParam(required)` 가 이미 정하고 실측에서도
     * 맞았다. 그 자리가 틀어지면 조건을 생략할 수 없거나 id 없이 부를 수 있게 된다.
     */
    @Test
    @DisplayName("상위 인자는 그대로다 — id 는 필수, 조건은 선택")
    void topLevelParametersAreUntouched() {
        McpSchema.Tool tool = tool("list_sub_works");
        assertThat(tool.inputSchema().required()).isNullOrEmpty();

        McpSchema.Tool detail = tool("get_sub_work");
        assertThat(detail.inputSchema().required()).containsExactly("subWorkId");

        McpSchema.Tool agenda = tool("add_meeting_agenda");
        assertThat(agenda.inputSchema().required())
                .containsExactlyInAnyOrder("meetingId", "request");
    }

    // ── 도우미 ────────────────────────────────────────────────

    private List<String> nestedRequired(String toolName, String parameter) {
        return required(property(toolName, parameter));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> property(String toolName, String parameter) {
        Object node = tool(toolName).inputSchema().properties().get(parameter);
        assertThat(node).as("%s 의 인자 %s", toolName, parameter).isInstanceOf(Map.class);
        return (Map<String, Object>) node;
    }

    @SuppressWarnings("unchecked")
    private List<String> required(Map<String, Object> schema) {
        Object node = schema.get("required");
        return node == null ? List.of() : new ArrayList<>((List<String>) node);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> child(Map<String, Object> schema, String name) {
        return (Map<String, Object>) ((Map<String, Object>) schema.get("properties")).get(name);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> itemsOf(Map<String, Object> arraySchema) {
        return (Map<String, Object>) arraySchema.get("items");
    }

    private McpSchema.Tool tool(String name) {
        Map<String, McpSchema.Tool> tools = toolsByName();
        assertThat(tools).containsKey(name);
        return tools.get(name);
    }

    private Map<String, McpSchema.Tool> cached;

    private Map<String, McpSchema.Tool> toolsByName() {
        if (cached == null) {
            HttpClientStreamableHttpTransport transport =
                    HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                            .endpoint(McpProtectedResource.MCP_PATH)
                            .customizeRequest(
                                    b -> b.header("Authorization", "Bearer " + UUID.randomUUID()))
                            .build();
            try (McpSyncClient client =
                    McpClient.sync(transport).requestTimeout(Duration.ofSeconds(20)).build()) {
                client.initialize();
                Map<String, McpSchema.Tool> map = new LinkedHashMap<>();
                client.listTools().tools().forEach(t -> map.put(t.name(), t));
                cached = map;
            }
        }
        return cached;
    }
}
