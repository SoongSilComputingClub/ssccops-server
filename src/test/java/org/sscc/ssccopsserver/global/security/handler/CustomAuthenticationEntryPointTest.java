package org.sscc.ssccopsserver.global.security.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.BadCredentialsException;
import org.sscc.ssccopsserver.global.apipayload.code.error.CommonErrorCode;
import org.sscc.ssccopsserver.global.config.AppPublicBaseUrl;
import org.sscc.ssccopsserver.global.logging.EcsJsonEncoder;
import org.sscc.ssccopsserver.global.mcp.McpProtectedResource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/*
 * 401 응답 모양과 로그 한 줄 (#389 · ssccops#319).
 *
 * 로그는 핸들러 로거에 ListAppender를 달아 잡고 실제 인코더(EcsJsonEncoder)로 굳혀 JSON을 본다 —
 * Logstash·Kibana가 보는 것이 그 JSON이다(`AuditLogTest`와 같은 방식). 전역 LoggerContext를 쓰는
 * 이유는 `EcsJsonEncoderTest`에 있다.
 */
class CustomAuthenticationEntryPointTest {

    private static final String BEARER = "Bearer eyJhbGciOiJIUzI1NiJ9.secret-token-value";

    private CustomAuthenticationEntryPoint authenticationEntryPoint;
    private MockHttpServletRequest request;
    private HttpServletResponse response;
    private StringWriter stringWriter;
    private ObjectMapper objectMapper;
    private ListAppender<ILoggingEvent> captured;
    private EcsJsonEncoder encoder;

    @BeforeEach
    void setUp() throws IOException {
        authenticationEntryPoint =
                new CustomAuthenticationEntryPoint(
                        new McpProtectedResource(
                                new AppPublicBaseUrl("https://api.test.local"),
                                "https://example.supabase.co/auth/v1"));
        request = new MockHttpServletRequest("GET", "/v1/auth/session");
        request.setQueryString("token=query-secret");
        request.addHeader("Authorization", BEARER);
        request.addHeader("User-Agent", "claude-code/1.0");
        response = mock(HttpServletResponse.class);
        stringWriter = new StringWriter();
        objectMapper = new ObjectMapper();

        when(response.getWriter()).thenReturn(new PrintWriter(stringWriter));

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        captured = new ListAppender<>();
        captured.setContext(context);
        captured.start();
        handlerLogger().addAppender(captured);
        encoder = new EcsJsonEncoder();
        encoder.setContext(context);
        encoder.setEnvironment("test");
        encoder.start();
    }

    @AfterEach
    void tearDown() {
        handlerLogger().detachAppender(captured);
        encoder.stop();
    }

    @Test
    @DisplayName("인증 실패 시 ApiResponse 형식으로 401 응답")
    void testAuthenticationFailure() throws IOException {
        // given
        BadCredentialsException exception = new BadCredentialsException("자격 증명 실패");

        // when
        authenticationEntryPoint.commence(request, response, exception);

        // then
        String responseBody = stringWriter.toString();
        JsonNode jsonNode = objectMapper.readTree(responseBody);

        assertThat(jsonNode.get("success").asBoolean()).isFalse();
        assertThat(jsonNode.get("code").asText()).isEqualTo(CommonErrorCode.UNAUTHORIZED.getCode());
        assertThat(jsonNode.get("message").asText()).isEqualTo("인증이 필요합니다. 로그인 후 다시 시도해주세요.");
        assertThat(jsonNode.get("data").isNull()).isTrue();
    }

    @Test
    @DisplayName("다양한 인증 예외에 대해 동일한 응답 형식 반환")
    void testVariousAuthenticationExceptions() throws IOException {
        // given
        BadCredentialsException exception = new BadCredentialsException("다른 인증 오류");

        // when
        authenticationEntryPoint.commence(request, response, exception);

        // then
        String responseBody = stringWriter.toString();
        JsonNode jsonNode = objectMapper.readTree(responseBody);

        assertThat(jsonNode.get("success").asBoolean()).isFalse();
        assertThat(jsonNode.get("code").asText()).isEqualTo(CommonErrorCode.UNAUTHORIZED.getCode());
    }

    @Test
    @DisplayName("JSON 응답이 올바른 구조를 가지고 있음")
    void testJsonStructure() throws IOException {
        // given
        BadCredentialsException exception = new BadCredentialsException("인증 실패");

        // when
        authenticationEntryPoint.commence(request, response, exception);

        // then
        String responseBody = stringWriter.toString();
        JsonNode jsonNode = objectMapper.readTree(responseBody);

        // ApiResponse의 4가지 필드 검증
        assertThat(jsonNode.has("success")).isTrue();
        assertThat(jsonNode.has("code")).isTrue();
        assertThat(jsonNode.has("message")).isTrue();
        assertThat(jsonNode.has("data")).isTrue();
    }

    /* 일반 경로의 401은 WARN이고 경로·메서드·UA가 ECS 중첩 필드로 실린다 */
    @Test
    @DisplayName("401 로그에 url.path·http.request.method·user_agent.original이 중첩 필드로 실린다")
    void logCarriesRequestContextAsNestedEcsFields() throws Exception {
        authenticationEntryPoint.commence(
                request, response, new BadCredentialsException("자격 증명 실패"));

        JsonNode json = onlyLine();
        assertThat(json.at("/log/level").asText()).isEqualTo("WARN");
        assertThat(json.get("message").asText()).isEqualTo("Authentication failed: 자격 증명 실패");
        assertThat(json.at("/url/path").asText()).isEqualTo("/v1/auth/session");
        assertThat(json.at("/http/request/method").asText()).isEqualTo("GET");
        assertThat(json.at("/user_agent/original").asText()).isEqualTo("claude-code/1.0");
        // 점이 든 평면 키가 아니다 — Logstash 조건문은 중첩 키만 찾는다
        json.fieldNames().forEachRemaining(name -> assertThat(name).doesNotContain("."));
    }

    /* 토큰 값·쿼리 문자열은 어디에도 없다 — 로그 열람 권한이 인증 권한이 되지 않게 */
    @Test
    @DisplayName("401 로그에 Authorization 값과 쿼리 문자열이 없다")
    void logNeverCarriesAuthorizationOrQueryString() throws Exception {
        authenticationEntryPoint.commence(
                request, response, new BadCredentialsException("자격 증명 실패"));

        String line = encode(captured.list.get(0));
        assertThat(line)
                .doesNotContain("secret-token-value")
                .doesNotContain("Bearer")
                .doesNotContain("query-secret")
                .doesNotContain("?token=");
    }

    /* `/mcp` 발견 프로브는 정상 연결의 첫 단계라 INFO — 같은 필드는 그대로 실린다 */
    @Test
    @DisplayName("/mcp 401은 INFO로 남는다")
    void mcpProbeIsInfo() throws Exception {
        request.setRequestURI("/mcp");
        request.setMethod("POST");

        authenticationEntryPoint.commence(request, response, new BadCredentialsException("토큰 없음"));

        JsonNode json = onlyLine();
        assertThat(json.at("/log/level").asText()).isEqualTo("INFO");
        assertThat(json.at("/url/path").asText()).isEqualTo("/mcp");
        assertThat(json.at("/http/request/method").asText()).isEqualTo("POST");
        assertThat(json.at("/user_agent/original").asText()).isEqualTo("claude-code/1.0");
        assertThat(captured.list.get(0).getLevel()).isEqualTo(Level.INFO);
    }

    @Test
    @DisplayName("/.well-known/** 401도 INFO로 남는다")
    void wellKnownProbeIsInfo() throws Exception {
        request.setRequestURI("/.well-known/oauth-authorization-server");

        authenticationEntryPoint.commence(request, response, new BadCredentialsException("토큰 없음"));

        JsonNode json = onlyLine();
        assertThat(json.at("/log/level").asText()).isEqualTo("INFO");
        assertThat(json.at("/url/path").asText())
                .isEqualTo("/.well-known/oauth-authorization-server");
    }

    /* `/mcpx` 같은 접두사 우연은 MCP가 아니다 — WARN 유지 */
    @Test
    @DisplayName("/mcp로 시작만 하는 다른 경로는 WARN")
    void mcpPrefixLookalikeStaysWarn() throws Exception {
        request.setRequestURI("/mcpx/anything");

        authenticationEntryPoint.commence(request, response, new BadCredentialsException("토큰 없음"));

        assertThat(onlyLine().at("/log/level").asText()).isEqualTo("WARN");
    }

    /* User-Agent가 없으면 빈 문자열을 지어내지 않고 객체 자체를 내지 않는다 */
    @Test
    @DisplayName("User-Agent가 없으면 user_agent 객체가 없다")
    void omitsUserAgentWhenHeaderMissing() throws Exception {
        MockHttpServletRequest bare = new MockHttpServletRequest("GET", "/v1/members");

        authenticationEntryPoint.commence(bare, response, new BadCredentialsException("x"));

        JsonNode json = onlyLine();
        assertThat(json.has("user_agent")).isFalse();
        assertThat(json.at("/url/path").asText()).isEqualTo("/v1/members");
    }

    private static Logger handlerLogger() {
        return (Logger) LoggerFactory.getLogger(CustomAuthenticationEntryPoint.class);
    }

    private JsonNode onlyLine() throws Exception {
        assertThat(captured.list).hasSize(1);
        return objectMapper.readTree(encode(captured.list.get(0)));
    }

    private String encode(ILoggingEvent event) {
        return new String(encoder.encode(event), StandardCharsets.UTF_8);
    }
}
