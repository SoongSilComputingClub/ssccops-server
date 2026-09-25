package org.sscc.ssccopsserver.global.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.LocalDate;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.CurriculumItemEntity;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramTypeRepository;
import org.sscc.ssccopsserver.domain.academicprogram.repository.CurriculumItemRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventClassificationRepository;
import org.sscc.ssccopsserver.domain.event.repository.EventRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormResponseHistoryRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.global.mcp.McpProtectedResource;
import org.sscc.ssccopsserver.support.AcademicProgramFixture;
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

import com.jayway.jsonpath.JsonPath;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;

/*
 * 학술 도구 W3 (#567 · ssccops#365) — MCP 클라이언트로 붙어 **정말 REST를 지나는지**.
 * `EventFormToolsIntegrationTest`와 같은 뼈대다(첫 가입자 = 최고관리자라 ACADEMIC_PROGRAM_MANAGE를 갖는다).
 *
 * ── 여기서 확인하는 것과 하지 않는 것 ──────────────────────────────────────
 *
 * 이 도구들은 **얇은 위임**이라 «로직»이 없다. 그래서 확인하는 것은 계산이 아니라 **배선**이다.
 *
 *  · 도구 하나가 **어느 경로·어느 메서드**로 가는가 — 경로 오타(`/sessions/`를 `/session/`로)는
 *    컴파일도 타입도 잡지 못하고, 얇을수록 그것이 유일한 실패 방식이다.
 *  · 조건 record가 **쿼리로 실려 가는가**.
 *  · 실패가 **깨끗한 도구 오류**로 돌아오는가 — `McpRestClient`가 `success:false`를
 *    `McpToolException`으로 바꾸고 mcp-annotations가 그것을 `isError`로 만든다. 모델이 읽는 것이
 *    그 메시지 하나이므로, 403·409가 스택 트레이스나 빈 본문이 되면 그 자체가 결함이다.
 *
 * 업무 규칙(누가 승인할 수 있나 · 어떤 상태에서 어디로 가나)은 **여기서 다시 보지 않는다** —
 * `AcademicProgramReviewControllerTest`·`…AttendanceControllerTest`가 REST 층에서 이미 본다.
 * 도구가 그 층을 지나가므로 여기서 또 보면 같은 규칙이 두 벌이 되고, 규칙이 바뀔 때 도구 테스트가
 * 함께 빨개진다(`AcademicProgramFixture` 주석이 같은 이유로 기획안 내용을 흉내 내지 않는다).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties =
                "spring.datasource.url="
                        + "jdbc:h2:mem:mcp-academic-tools;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AcademicToolsIntegrationTest {

    private static final UUID FOUNDER = UUID.randomUUID();

    @LocalServerPort private int port;
    @Autowired private MockMvc mockMvc;

    @Autowired private MemberRepository memberRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private EventClassificationRepository eventClassificationRepository;
    @Autowired private AcademicProgramRepository academicProgramRepository;
    @Autowired private AcademicProgramTypeRepository academicProgramTypeRepository;
    @Autowired private CurriculumItemRepository curriculumItemRepository;
    @Autowired private FormRepository formRepository;
    @Autowired private FormResponseHistoryRepository formResponseHistoryRepository;

    private Long programId;
    private Long sessionId;

    @BeforeAll
    void prepare() throws Exception {
        mockMvc.perform(signup(FOUNDER, body("김도현", "20200031"))).andExpect(status().isCreated());

        /*
         * 활동은 등록 API 가 없다 — 기획안 승인 이관으로만 생긴다(`academicprogram/AGENTS.md`).
         * 그래서 픽스처가 리포지토리로 그 결과 모양을 만든다. 제출자를 첫 가입자로 두어
         * **리더와 학술국장이 같은 사람**이 되게 했다 — 이 테스트가 보는 것은 권한 갈림이 아니라
         * 배선이라, 신원을 둘로 나누면 픽스처만 커지고 얻는 것이 없다.
         */
        MemberEntity founder = memberRepository.findByAuthUserId(FOUNDER).orElseThrow();
        AcademicProgramEntity program =
                AcademicProgramFixture.save(
                        eventRepository,
                        eventClassificationRepository,
                        academicProgramRepository,
                        academicProgramTypeRepository,
                        curriculumItemRepository,
                        formRepository,
                        formResponseHistoryRepository,
                        "STUDY",
                        "알고리즘 스터디",
                        founder,
                        List.of("OT", "1주차"));
        programId = program.getId();

        CurriculumItemEntity firstItem =
                curriculumItemRepository.findByAcademicProgramIdOrderBySeqnoAsc(programId).stream()
                        .findFirst()
                        .orElseThrow();
        sessionId = submitSession(firstItem.getId());
    }

    // ══ 읽기 여섯 ═══════════════════════════════════════════════

    @Test
    @DisplayName("활동 목록·상세·팀원이 REST를 지난다")
    void programReadsGoThroughRest() {
        try (McpSyncClient client = connect()) {
            String list = text(call(client, "list_academic_programs", Map.of()));
            assertThat(list).contains("알고리즘 스터디").doesNotContain("\"success\"");

            String detail =
                    text(
                            call(
                                    client,
                                    "get_academic_program",
                                    Map.of("academicProgramId", programId)));
            assertThat(detail).contains("알고리즘 스터디");

            String members =
                    text(
                            call(
                                    client,
                                    "list_academic_program_members",
                                    Map.of("academicProgramId", programId)));
            assertThat(members).isNotBlank();
        }
    }

    @Test
    @DisplayName("회차 목록·상세와 검토 대기 목록이 REST를 지난다")
    void sessionReadsGoThroughRest() {
        try (McpSyncClient client = connect()) {
            String sessions =
                    text(
                            call(
                                    client,
                                    "list_academic_sessions",
                                    Map.of("academicProgramId", programId)));
            assertThat(sessions).contains("\"sessionId\":" + sessionId);

            String detail =
                    text(
                            call(
                                    client,
                                    "get_academic_session",
                                    Map.of(
                                            "academicProgramId",
                                            programId,
                                            "sessionId",
                                            sessionId)));
            assertThat(detail).contains("SUBMITTED");

            /*
             * 활동을 가리지 않고 모으는 목록이다 — «승인할 게 뭐 있어»의 답이고 이 파도의 요점이다.
             * 방금 제출한 회차가 여기 보여야 한다.
             */
            String pending = text(call(client, "list_academic_sessions_to_review", Map.of()));
            assertThat(pending).contains("\"sessionId\":" + sessionId);
        }
    }

    @Test
    @DisplayName("출석부 조회가 회차 경로로 간다 — eventPtcpId 를 여기서 얻는다")
    void attendanceListGoesToTheSessionPath() {
        try (McpSyncClient client = connect()) {
            McpSchema.CallToolResult result =
                    call(
                            client,
                            "list_academic_attendances",
                            Map.of("academicProgramId", programId, "sessionId", sessionId));

            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        }
    }

    @Test
    @DisplayName("모집 지원 목록은 모집 시작 전이면 그 이유를 문장으로 돌려준다")
    void recruitmentListGoesToTheRecruitmentPath() {
        try (McpSyncClient client = connect()) {
            McpSchema.CallToolResult result =
                    call(
                            client,
                            "list_academic_recruitment_applications",
                            Map.of("academicProgramId", programId));

            /*
             * 픽스처의 활동은 모집을 시작한 적이 없다. 그 경로는 **빈 배열이 아니라 409
             * RECRUITMENT_NOT_STARTED** 로 답하도록 설계돼 있다(컨트롤러 주석 — «빈 배열은
             * '아무도 지원하지 않았다'로 읽힌다»). 그래서 여기서 보는 것은 목록 내용이 아니라
             * **그 거절이 도구 오류로, 읽을 수 있는 한 문장으로** 돌아오는가다.
             *
             * 학번(`stdntNo`)이 지워지는지는 여기서 볼 수 없다 — 이 응답에는 회원이 실리지
             * 않는다. 그 보장은 `ToolOutputRedactorTest`(실제 JSON 트리)와
             * `ToolOutputRedactorCoverageTest`(이름 전수)가 진다.
             */
            assertThat(result.isError()).isEqualTo(Boolean.TRUE);
            assertThat(text(result)).isNotBlank().doesNotContain("stackTrace");
        }
    }

    // ══ 검토 ═══════════════════════════════════════════════════

    @Test
    @DisplayName("회차 승인이 REST 를 지나 상태를 옮긴다")
    void approvingASessionGoesThroughRest() {
        try (McpSyncClient client = connect()) {
            String approved =
                    text(
                            call(
                                    client,
                                    "transition_academic_session",
                                    Map.of(
                                            "academicProgramId", programId,
                                            "sessionId", sessionId,
                                            "request", Map.of("transition", "APPROVE"))));

            assertThat(approved).contains("APPROVED");
        }
    }

    @Test
    @DisplayName("갈 수 없는 전이는 깨끗한 도구 오류다 — 모델이 읽는 것은 그 문장뿐이다")
    void impossibleTransitionComesBackAsAToolError() {
        try (McpSyncClient client = connect()) {
            /*
             * 활동은 `APPROVED` 에서만 모집을 시작할 수 있다. 픽스처의 활동은 그 상태가 아니므로
             * 서버가 409 로 거절한다 — 그것이 **스택 트레이스나 빈 본문이 아니라** 한 문장으로
             * 돌아오는지가 이 테스트의 전부다.
             */
            McpSchema.CallToolResult result =
                    call(
                            client,
                            "transition_academic_program",
                            Map.of(
                                    "academicProgramId",
                                    programId,
                                    "request",
                                    Map.of("transition", "START_RECRUITMENT")));

            assertThat(result.isError()).isEqualTo(Boolean.TRUE);
            assertThat(text(result))
                    .isNotBlank()
                    .doesNotContain("stackTrace")
                    .doesNotContain("Exception in thread");
        }
    }

    @Test
    @DisplayName("없는 대상을 준 쓰기 도구도 깨끗한 오류로 끝난다")
    void writesWithUnknownTargetsFailCleanly() {
        try (McpSyncClient client = connect()) {
            McpSchema.CallToolResult select =
                    call(
                            client,
                            "select_academic_recruitment",
                            Map.of(
                                    "academicProgramId",
                                    programId,
                                    "request",
                                    Map.of(
                                            "selections",
                                            List.of(
                                                    Map.of(
                                                            "formRspnsId",
                                                            999_999,
                                                            "ptcpSttsCd",
                                                            "CONFIRMED")))));
            assertThat(select.isError()).isEqualTo(Boolean.TRUE);
            assertThat(text(select)).isNotBlank().doesNotContain("stackTrace");

            McpSchema.CallToolResult correct =
                    call(
                            client,
                            "correct_academic_attendances",
                            Map.of(
                                    "academicProgramId",
                                    programId,
                                    "sessionId",
                                    sessionId,
                                    "request",
                                    Map.of(
                                            "attendances",
                                            List.of(
                                                    Map.of(
                                                            "eventPtcpId",
                                                            999_999,
                                                            "atndYn",
                                                            true)))));
            assertThat(correct.isError()).isEqualTo(Boolean.TRUE);
            assertThat(text(correct)).isNotBlank().doesNotContain("stackTrace");
        }
    }

    // ══ 뼈대 ═══════════════════════════════════════════════════

    private Long submitSession(Long curriculumItemId) {
        try {
            String response =
                    mockMvc.perform(
                                    authorized(
                                                    post(
                                                            "/v1/academic-programs/"
                                                                    + programId
                                                                    + "/sessions"))
                                            .content(
                                                    """
                                                    {"curriculumItemId":%d,"actlYmd":"%s",
                                                     "prgrsCn":"1회차 진행","attendances":[]}
                                                    """
                                                            .formatted(
                                                                    curriculumItemId,
                                                                    LocalDate.of(2026, 9, 5))))
                            .andExpect(status().isCreated())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            return JsonPath.parse(response).read("$.data.sessionId", Long.class);
        } catch (Exception ex) {
            throw new IllegalStateException("회차 제출 픽스처 실패", ex);
        }
    }

    private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + FOUNDER)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private MockHttpServletRequestBuilder signup(UUID token, String body) {
        return post("/v1/members/signup")
                .header("Authorization", "Bearer " + token)
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

    private McpSyncClient connect() {
        McpSyncClient client =
                McpClient.sync(
                                HttpClientStreamableHttpTransport.builder(
                                                "http://localhost:" + port)
                                        .endpoint(McpProtectedResource.MCP_PATH)
                                        .customizeRequest(
                                                request ->
                                                        request.header(
                                                                "Authorization",
                                                                "Bearer " + FOUNDER))
                                        .build())
                        .requestTimeout(Duration.ofSeconds(20))
                        .build();
        client.initialize();
        return client;
    }

    private static McpSchema.CallToolResult call(
            McpSyncClient client, String tool, Map<String, Object> arguments) {
        return client.callTool(new McpSchema.CallToolRequest(tool, arguments));
    }

    private static String text(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .reduce("", String::concat);
    }
}
