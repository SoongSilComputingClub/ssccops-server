package org.sscc.ssccopsserver.global.logging;

import java.io.IOException;

import net.logstash.logback.composite.loggingevent.ArgumentsJsonProvider;
import net.logstash.logback.composite.loggingevent.LoggingEventFormattedTimestampJsonProvider;
import net.logstash.logback.composite.loggingevent.LoggingEventJsonProviders;
import net.logstash.logback.composite.loggingevent.LoggingEventNestedJsonProvider;
import net.logstash.logback.composite.loggingevent.LoggingEventPatternJsonProvider;
import net.logstash.logback.composite.loggingevent.MessageJsonProvider;
import net.logstash.logback.composite.loggingevent.StackTraceJsonProvider;
import net.logstash.logback.composite.loggingevent.ThrowableClassNameJsonProvider;
import net.logstash.logback.composite.loggingevent.ThrowableMessageJsonProvider;
import net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder;

import com.fasterxml.jackson.core.JsonGenerator;

import ch.qos.logback.classic.spi.ILoggingEvent;

/*
 * 로그 한 줄을 ECS 구조의 JSON으로 만든다 (ssccops#298 · ADR-0024).
 *
 * 필드가 **중첩**이다 — `{"log":{"level":"INFO"}}`이지 `{"log.level":"INFO"}`가 아니다.
 * Elasticsearch는 둘을 같게 보지만 Logstash는 다르다: 파이프라인의 `[event][dataset]` 조건은
 * 중첩 키만 찾고, 점이 든 평면 키는 그냥 이름이 "event.dataset"인 별개 필드다. 애플리케이션이
 * 처음부터 중첩으로 내면 Logstash에 de_dot 같은 손질이 필요 없다.
 *
 * XML이 아니라 코드인 이유: stdout 과 Logstash TCP 두 appender가 같은 모양을 내야 하는데
 * logback은 encoder 정의를 공유할 수 없어 XML로 하면 같은 블록이 둘이 된다. 클래스 하나를
 * 양쪽에서 가리키면 갈라질 자리가 없고, Spring 없이 인코더만 단위 테스트할 수 있다.
 *
 * 무엇을 내는가:
 *   @timestamp · message · log.level · log.logger · process.thread.name ·
 *   service.name · service.environment · trace.id · span.id(MDC에 있을 때만) ·
 *   error.type · error.message · error.stack_trace(예외가 있을 때만) ·
 *   그리고 StructuredArguments로 넘긴 것 전부(감사 로그의 event·user·audit이 이 길로 온다)
 *
 * `event.dataset`은 여기서 박지 않는다. 일반 로그는 Logstash가 `ssccops.application`을 기본값으로
 * 채우고, 감사 로그는 AuditLog가 인자로 `event` 객체를 싣는다 — 인코더가 `event`를 먼저 내면
 * 같은 키가 두 번 나가 JSON이 깨진다.
 */
public class EcsJsonEncoder extends LoggingEventCompositeJsonEncoder {

    private String serviceName = "ssccops-server";
    private String environment = "local";

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    @Override
    public void start() {
        LoggingEventJsonProviders providers = new LoggingEventJsonProviders();

        LoggingEventFormattedTimestampJsonProvider timestamp =
                new LoggingEventFormattedTimestampJsonProvider();
        timestamp.setFieldName("@timestamp");
        timestamp.setTimeZone("UTC");
        providers.addTimestamp(timestamp);

        providers.addMessage(new MessageJsonProvider());

        // 고정 구조는 패턴 하나로 — omitEmptyFields 가 MDC 없는 trace/span 을 통째로 뺀다
        LoggingEventPatternJsonProvider pattern = new LoggingEventPatternJsonProvider();
        pattern.setOmitEmptyFields(true);
        pattern.setPattern(
                "{"
                        + "\"log\":{\"level\":\"%level\",\"logger\":\"%logger\"},"
                        + "\"process\":{\"thread\":{\"name\":\"%thread\"}},"
                        + "\"service\":{\"name\":\""
                        + serviceName
                        + "\","
                        + "\"environment\":\""
                        + environment
                        + "\"},"
                        + "\"trace\":{\"id\":\"%mdc{trace_id}\"},"
                        + "\"span\":{\"id\":\"%mdc{span_id}\"}"
                        + "}");
        providers.addPattern(pattern);

        // 예외는 error.* 아래로. 예외가 없는 줄에는 "error" 키 자체가 없다 (ErrorJsonProvider)
        LoggingEventNestedJsonProvider error = new ErrorJsonProvider();
        error.setFieldName("error");
        LoggingEventJsonProviders errorProviders = new LoggingEventJsonProviders();
        ThrowableClassNameJsonProvider type = new ThrowableClassNameJsonProvider();
        type.setFieldName("type");
        errorProviders.addThrowableClassName(type);
        ThrowableMessageJsonProvider message = new ThrowableMessageJsonProvider();
        message.setFieldName("message");
        errorProviders.addThrowableMessage(message);
        StackTraceJsonProvider stackTrace = new StackTraceJsonProvider();
        stackTrace.setFieldName("stack_trace");
        errorProviders.addStackTrace(stackTrace);
        error.setProviders(errorProviders);
        providers.addNestedField(error);

        // StructuredArguments — 감사 로그의 event·user·source·audit 객체가 이 길로 루트에 실린다
        providers.addArguments(new ArgumentsJsonProvider());

        setProviders(providers);
        super.start();
    }

    /*
     * 중첩 provider는 안이 비어도 `"error":{}`를 쓴다. 그러면 모든 줄에 빈 error 객체가 실려
     * Kibana에서 «error가 있는 로그»를 고를 수 없다 — 예외가 없으면 아예 쓰지 않는다.
     */
    private static final class ErrorJsonProvider extends LoggingEventNestedJsonProvider {
        @Override
        public void writeTo(JsonGenerator generator, ILoggingEvent event) throws IOException {
            if (event.getThrowableProxy() == null) {
                return;
            }
            super.writeTo(generator, event);
        }
    }
}
