package org.sscc.ssccopsserver.support;

import java.util.List;

import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/*
 * 특정 클래스가 남긴 로그를 테스트에서 들여다보기 위한 픽스처 (#118).
 *
 * 운영 도메인의 거절 세 층은 응답이 모두 403 FORBIDDEN 하나라, 어느 층에서 막혔는지는 로그로만
 * 갈린다 — 그 로그가 실제로 남는지는 응답을 보는 테스트로 확인할 수 없어 여기서 붙잡는다.
 *
 * try-with-resources로 쓰면 appender가 반드시 떨어진다. 붙인 채로 두면 뒤따르는 테스트의
 * 로그까지 쌓여 다른 테스트가 남긴 줄을 자기 것으로 착각한다.
 */
public final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private LogCapture(Class<?> type) {
        this.logger = (Logger) LoggerFactory.getLogger(type);
        appender.start();
        logger.addAppender(appender);
    }

    public static LogCapture of(Class<?> type) {
        return new LogCapture(type);
    }

    /** 지금까지 쌓인 WARN 줄을 치환까지 마친 문자열로. 레벨을 섞지 않는 것은 층별 레벨도 규약이기 때문이다 */
    public List<String> warnMessages() {
        return messagesAt(Level.WARN);
    }

    public List<String> errorMessages() {
        return messagesAt(Level.ERROR);
    }

    private List<String> messagesAt(Level level) {
        return appender.list.stream()
                .filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}
