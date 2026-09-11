package org.sscc.ssccopsserver.global.logging;

import static net.logstash.logback.argument.StructuredArguments.kv;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;

/*
 * 로그 한 줄의 모양 (ssccops#298 · ADR-0024).
 *
 * Logstash 파이프라인과 Kibana 가 이 이름을 본다. 여기서 깨지면 배포 뒤 «Kibana 에 안 보인다»로만
 * 드러나므로 필드 이름을 테스트로 못 박는다. 특히 **중첩**이어야 한다 — `log.level` 이 아니라
 * `log: {level}`. Logstash 조건문이 점이 든 평면 키를 못 찾는다.
 */
class EcsJsonEncoderTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private LoggerContext context;
    private EcsJsonEncoder encoder;

    @BeforeEach
    void setUp() {
        // 전역 컨텍스트를 쓴다 — logback 1.5부터 LoggingEvent가 MDC를 자기 컨텍스트의 어댑터에서
        // 읽으므로 새 LoggerContext()에는 테스트가 MDC에 넣은 값이 보이지 않는다
        context = (LoggerContext) LoggerFactory.getILoggerFactory();
        encoder = new EcsJsonEncoder();
        encoder.setContext(context);
        encoder.setServiceName("ssccops-server");
        encoder.setEnvironment("test");
        encoder.start();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        encoder.stop();
    }

    @Test
    void writesEcsFieldsNested() throws Exception {
        JsonNode json = encode(event(Level.INFO, "hello", null));

        assertThat(json.get("@timestamp").asText()).endsWith("Z");
        assertThat(json.get("message").asText()).isEqualTo("hello");
        assertThat(json.at("/log/level").asText()).isEqualTo("INFO");
        assertThat(json.at("/log/logger").asText()).isEqualTo("org.sscc.Test");
        assertThat(json.at("/service/name").asText()).isEqualTo("ssccops-server");
        assertThat(json.at("/service/environment").asText()).isEqualTo("test");
        assertThat(json.at("/process/thread/name").asText()).isNotBlank();
        // 점이 든 평면 키가 하나도 없다
        json.fieldNames().forEachRemaining(name -> assertThat(name).doesNotContain("."));
    }

    // MDC 에 trace 가 없으면 빈 문자열이 아니라 키 자체가 없다 — Kibana 에서 «trace.id: ""» 로 세지 않게
    @Test
    void omitsTraceWhenMdcIsEmpty() throws Exception {
        JsonNode json = encode(event(Level.INFO, "no trace", null));

        assertThat(json.has("trace")).isFalse();
        assertThat(json.has("span")).isFalse();
        assertThat(json.has("error")).isFalse();
    }

    @Test
    void carriesTraceFromMdc() throws Exception {
        MDC.put("trace_id", "4bf92f3577b34da6a3ce929d0e0e4736");
        MDC.put("span_id", "00f067aa0ba902b7");

        JsonNode json = encode(event(Level.INFO, "traced", null));

        assertThat(json.at("/trace/id").asText()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(json.at("/span/id").asText()).isEqualTo("00f067aa0ba902b7");
    }

    @Test
    void putsExceptionUnderError() throws Exception {
        JsonNode json =
                encode(event(Level.ERROR, "boom", new IllegalStateException("pool exhausted")));

        assertThat(json.at("/error/type").asText()).isEqualTo("IllegalStateException");
        assertThat(json.at("/error/message").asText()).isEqualTo("pool exhausted");
        assertThat(json.at("/error/stack_trace").asText())
                .contains("IllegalStateException")
                .contains("EcsJsonEncoderTest");
    }

    /* 감사 로그가 쓰는 길 — StructuredArguments 의 객체가 루트에 그대로 중첩으로 실린다 */
    @Test
    void structuredArgumentsBecomeNestedObjects() throws Exception {
        LoggingEvent event = event(Level.INFO, "Member grade changed", null);
        event.setArgumentArray(
                new Object[] {
                    kv(
                            "event",
                            Map.of("dataset", "ssccops.audit", "action", "member.grade.change")),
                    kv("audit", Map.of("target", Map.of("type", "member", "id", "42")))
                });

        JsonNode json = encode(event);

        assertThat(json.at("/event/dataset").asText()).isEqualTo("ssccops.audit");
        assertThat(json.at("/event/action").asText()).isEqualTo("member.grade.change");
        assertThat(json.at("/audit/target/id").asText()).isEqualTo("42");
    }

    private LoggingEvent event(Level level, String message, Throwable throwable) {
        Logger logger = context.getLogger("org.sscc.Test");
        return new LoggingEvent("org.sscc.Test", logger, level, message, throwable, null);
    }

    private JsonNode encode(LoggingEvent event) throws Exception {
        String line = new String(encoder.encode(event), StandardCharsets.UTF_8);
        assertThat(line).endsWith(System.lineSeparator());
        return mapper.readTree(line);
    }
}
