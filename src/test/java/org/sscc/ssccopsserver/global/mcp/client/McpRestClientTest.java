package org.sscc.ssccopsserver.global.mcp.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.sscc.ssccopsserver.domain.member.dto.MemberSummaryResponse;
import org.sscc.ssccopsserver.global.security.AuthenticatedUser;
import org.sscc.ssccopsserver.global.security.jwt.SupabaseAuthenticationToken;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.modelcontextprotocol.common.McpTransportContext;

/**
 * 공통 클라이언트(#385) — 봉투 벗기기 · 오류 변환 · 페이징 · 타임아웃 · Bearer 전달.
 *
 * <p>스프링 없이 JDK HttpServer 하나로 REST를 흉내 낸다. 무엇을 받았는지(경로·쿼리·Authorization)를 기록해 둬서 «어떤 요청이 나갔는가»까지
 * 본다.
 */
class McpRestClientTest {

    private static HttpServer server;
    private static final List<String> receivedPaths = new CopyOnWriteArrayList<>();
    private static final List<String> receivedAuthorizations = new CopyOnWriteArrayList<>();

    private static final String MEMBER_JSON =
            "{\"memberId\":3,\"name\":\"김도현\",\"phoneNumber\":\"010-1234-5678\","
                    + "\"email\":\"kim@sscc.org\",\"studentNumber\":\"20200001\","
                    + "\"membershipGradeCode\":\"TEMP\",\"roles\":[]}";

    private McpRestClient client;
    private final McpTransportContext context =
            McpTransportContext.create(Map.of("authorization", "Bearer ctx-token"));

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        // 기본 executor는 단일 스레드라 /slow 핸들러가 자는 동안 다음 테스트의 요청까지 기다리게 된다
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext(
                "/",
                exchange -> {
                    receivedPaths.add(
                            exchange.getRequestURI().getPath()
                                    + (exchange.getRequestURI().getRawQuery() == null
                                            ? ""
                                            : "?" + exchange.getRequestURI().getRawQuery()));
                    receivedAuthorizations.add(
                            exchange.getRequestHeaders().getFirst("Authorization"));
                    route(exchange);
                });
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @BeforeEach
    void setUp() {
        receivedPaths.clear();
        receivedAuthorizations.clear();
        SecurityContextHolder.clearContext();
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        client =
                new McpRestClient(
                        mapper,
                        new BearerTokenSource(),
                        new ToolOutputRedactor(),
                        () -> "http://localhost:" + server.getAddress().getPort(),
                        Duration.ofSeconds(1),
                        Duration.ofMillis(400));
    }

    @Test
    @DisplayName("봉투를 벗기고 개인정보를 걷어낸 뒤 record로 굳힌다")
    void unwrapsAndRedacts() {
        MemberSummaryResponse member =
                client.get(context, "/v1/members/3", MemberSummaryResponse.class);

        assertThat(member.memberId()).isEqualTo(3L);
        assertThat(member.name()).isEqualTo("김도현");
        assertThat(member.phoneNumber()).isNull();
        assertThat(member.email()).isNull();
        assertThat(member.studentNumber()).isNull();
        assertThat(receivedAuthorizations).containsExactly("Bearer ctx-token");
    }

    @Test
    @DisplayName("SecurityContext의 Jwt가 전송 컨텍스트보다 먼저다")
    void securityContextTokenWins() {
        Jwt jwt =
                Jwt.withTokenValue("sc-token")
                        .header("alg", "none")
                        .subject("s")
                        .issuedAt(java.time.Instant.now())
                        .expiresAt(java.time.Instant.now().plusSeconds(60))
                        .build();
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new SupabaseAuthenticationToken(
                                new AuthenticatedUser(
                                        java.util.UUID.randomUUID(), null, null, "google", null),
                                jwt));

        client.get(context, "/v1/members/3", MemberSummaryResponse.class);

        assertThat(receivedAuthorizations).containsExactly("Bearer sc-token");
    }

    @Test
    @DisplayName("토큰이 어디에도 없으면 호출하지 않고 도구 오류다")
    void noTokenIsAToolError() {
        assertThatThrownBy(
                        () ->
                                client.get(
                                        McpTransportContext.EMPTY,
                                        "/v1/members/3",
                                        MemberSummaryResponse.class))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("인증 토큰");
        assertThat(receivedPaths).isEmpty();
    }

    @Test
    @DisplayName("403 FORBIDDEN은 «재시도해도 같다»로 끝난다")
    void forbiddenBecomesFinalToolError() {
        assertThatThrownBy(() -> client.get(context, "/forbidden", MemberSummaryResponse.class))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("권한이 없습니다")
                .hasMessageContaining("재시도해도");
    }

    @Test
    @DisplayName("SIGNUP_REQUIRED는 «가입 필요»로 끝난다")
    void signupRequiredBecomesFinalToolError() {
        assertThatThrownBy(
                        () -> client.get(context, "/signup-required", MemberSummaryResponse.class))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("가입이 필요합니다");
    }

    @Test
    @DisplayName("그 밖의 오류는 code와 message를 그대로 싣는다")
    void otherErrorsCarryCodeAndMessage() {
        assertThatThrownBy(() -> client.get(context, "/bad-request", MemberSummaryResponse.class))
                .isInstanceOf(McpToolException.class)
                .hasMessage("[VALIDATION_FAILED] size는 100 이하여야 합니다.");
    }

    @Test
    @DisplayName("목록은 커서를 최대 3페이지까지 따라가고 hasMore·nextCursor를 남긴다")
    void listFollowsCursorUpToThreePages() {
        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put("size", 1);
        condition.put("keyword", "a+b");
        McpListResult<MemberSummaryResponse> result =
                client.getList(context, "/paged", condition, MemberSummaryResponse.class);

        assertThat(result.items()).hasSize(3);
        assertThat(result.pagesFetched()).isEqualTo(3);
        assertThat(result.hasMore()).isTrue();
        assertThat(result.nextCursor()).isEqualTo("c3");
        assertThat(result.totalCount()).isEqualTo(10);
        // `+`가 %2B로 나가야 서버가 공백으로 읽지 않는다 · 커서는 두 번째 호출부터 붙는다
        assertThat(receivedPaths.get(0)).isEqualTo("/paged?size=1&keyword=a%2Bb");
        assertThat(receivedPaths.get(1)).isEqualTo("/paged?size=1&keyword=a%2Bb&cursor=c1");
        assertThat(receivedPaths.get(2)).isEqualTo("/paged?size=1&keyword=a%2Bb&cursor=c2");
    }

    @Test
    @DisplayName("읽기 타임아웃은 «지연» 도구 오류다")
    void readTimeoutBecomesToolError() {
        assertThatThrownBy(() -> client.get(context, "/slow", MemberSummaryResponse.class))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("지연");
    }

    private static void route(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery();
        switch (path) {
            case "/forbidden" -> respond(exchange, 403, fail("FORBIDDEN", "권한이 없습니다."));
            case "/signup-required" ->
                    respond(exchange, 403, fail("SIGNUP_REQUIRED", "회원 가입이 필요합니다."));
            case "/bad-request" ->
                    respond(exchange, 400, fail("VALIDATION_FAILED", "size는 100 이하여야 합니다."));
            case "/slow" -> {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                respond(exchange, 200, success(MEMBER_JSON, null));
            }
            case "/paged" -> {
                String cursor =
                        query != null && query.contains("cursor=c")
                                ? query.substring(query.indexOf("cursor=") + 7)
                                : "";
                String next = cursor.isEmpty() ? "c1" : "c" + (cursor.charAt(1) - '0' + 1);
                respond(
                        exchange,
                        200,
                        success(
                                "[" + MEMBER_JSON + "]",
                                "{\"size\":1,\"sort\":\"dueAt\",\"nextCursor\":\""
                                        + next
                                        + "\",\"hasNext\":true,\"totalCount\":10,\"overallCount\":10}"));
            }
            default -> respond(exchange, 200, success(MEMBER_JSON, null));
        }
    }

    private static String success(String data, String page) {
        return "{\"success\":true,\"code\":\"COMMON200\",\"message\":\"OK\",\"data\":"
                + data
                + (page == null ? "" : ",\"page\":" + page)
                + "}";
    }

    private static String fail(String code, String message) {
        return "{\"success\":false,\"code\":\""
                + code
                + "\",\"message\":\""
                + message
                + "\",\"data\":null}";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
