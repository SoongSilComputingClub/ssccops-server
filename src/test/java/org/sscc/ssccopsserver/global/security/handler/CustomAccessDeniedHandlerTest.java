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
import org.springframework.security.access.AccessDeniedException;
import org.sscc.ssccopsserver.global.apipayload.code.error.CommonErrorCode;
import org.sscc.ssccopsserver.global.logging.EcsJsonEncoder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/*
 * 403 응답 모양과 로그 한 줄 (#389 · ssccops#319). 로그 검증 방식은 CustomAuthenticationEntryPointTest와 같다.
 */
class CustomAccessDeniedHandlerTest {

    private static final String BEARER = "Bearer eyJhbGciOiJIUzI1NiJ9.secret-token-value";

    private CustomAccessDeniedHandler accessDeniedHandler;
    private MockHttpServletRequest request;
    private HttpServletResponse response;
    private StringWriter stringWriter;
    private ObjectMapper objectMapper;
    private ListAppender<ILoggingEvent> captured;
    private EcsJsonEncoder encoder;

    @BeforeEach
    void setUp() throws IOException {
        accessDeniedHandler = new CustomAccessDeniedHandler();
        request = new MockHttpServletRequest("DELETE", "/v1/members/7");
        request.setQueryString("token=query-secret");
        request.addHeader("Authorization", BEARER);
        request.addHeader("User-Agent", "Mozilla/5.0 test");
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
    @DisplayName("접근 거부 시 ApiResponse 형식으로 403 응답")
    void testAccessDenied() throws IOException {
        // given
        AccessDeniedException exception = new AccessDeniedException("접근 거부");

        // when
        accessDeniedHandler.handle(request, response, exception);

        // then
        String responseBody = stringWriter.toString();
        JsonNode jsonNode = objectMapper.readTree(responseBody);

        assertThat(jsonNode.get("success").asBoolean()).isFalse();
        assertThat(jsonNode.get("code").asText()).isEqualTo(CommonErrorCode.FORBIDDEN.getCode());
        assertThat(jsonNode.get("message").asText()).isEqualTo("접근 권한이 없습니다.");
        assertThat(jsonNode.get("data").isNull()).isTrue();
    }

    @Test
    @DisplayName("다양한 접근 거부 예외에 대해 동일한 응답 형식 반환")
    void testVariousAccessDeniedExceptions() throws IOException {
        // given
        AccessDeniedException exception = new AccessDeniedException("권한 부족");

        // when
        accessDeniedHandler.handle(request, response, exception);

        // then
        String responseBody = stringWriter.toString();
        JsonNode jsonNode = objectMapper.readTree(responseBody);

        assertThat(jsonNode.get("success").asBoolean()).isFalse();
        assertThat(jsonNode.get("code").asText()).isEqualTo(CommonErrorCode.FORBIDDEN.getCode());
    }

    @Test
    @DisplayName("JSON 응답이 올바른 구조를 가지고 있음")
    void testJsonStructure() throws IOException {
        // given
        AccessDeniedException exception = new AccessDeniedException("접근 거부");

        // when
        accessDeniedHandler.handle(request, response, exception);

        // then
        String responseBody = stringWriter.toString();
        JsonNode jsonNode = objectMapper.readTree(responseBody);

        // ApiResponse의 4가지 필드 검증
        assertThat(jsonNode.has("success")).isTrue();
        assertThat(jsonNode.has("code")).isTrue();
        assertThat(jsonNode.has("message")).isTrue();
        assertThat(jsonNode.has("data")).isTrue();
    }

    /* 403은 WARN 그대로이고 같은 ECS 필드가 실린다 — /mcp라도 INFO로 내리지 않는다 */
    @Test
    @DisplayName("403 로그는 WARN이고 url.path·http.request.method·user_agent.original이 실린다")
    void logCarriesRequestContextAndStaysWarn() throws Exception {
        accessDeniedHandler.handle(request, response, new AccessDeniedException("권한 부족"));

        JsonNode json = onlyLine();
        assertThat(json.at("/log/level").asText()).isEqualTo("WARN");
        assertThat(json.get("message").asText()).isEqualTo("Access denied: 권한 부족");
        assertThat(json.at("/url/path").asText()).isEqualTo("/v1/members/7");
        assertThat(json.at("/http/request/method").asText()).isEqualTo("DELETE");
        assertThat(json.at("/user_agent/original").asText()).isEqualTo("Mozilla/5.0 test");
        json.fieldNames().forEachRemaining(name -> assertThat(name).doesNotContain("."));
    }

    @Test
    @DisplayName("/mcp의 403도 WARN이다")
    void mcpForbiddenStaysWarn() throws Exception {
        request.setRequestURI("/mcp");

        accessDeniedHandler.handle(request, response, new AccessDeniedException("권한 부족"));

        assertThat(onlyLine().at("/log/level").asText()).isEqualTo("WARN");
    }

    @Test
    @DisplayName("403 로그에 Authorization 값과 쿼리 문자열이 없다")
    void logNeverCarriesAuthorizationOrQueryString() throws Exception {
        accessDeniedHandler.handle(request, response, new AccessDeniedException("권한 부족"));

        String line = encode(captured.list.get(0));
        assertThat(line)
                .doesNotContain("secret-token-value")
                .doesNotContain("Bearer")
                .doesNotContain("query-secret");
    }

    private static Logger handlerLogger() {
        return (Logger) LoggerFactory.getLogger(CustomAccessDeniedHandler.class);
    }

    private JsonNode onlyLine() throws Exception {
        assertThat(captured.list).hasSize(1);
        return objectMapper.readTree(encode(captured.list.get(0)));
    }

    private String encode(ILoggingEvent event) {
        return new String(encoder.encode(event), StandardCharsets.UTF_8);
    }
}
