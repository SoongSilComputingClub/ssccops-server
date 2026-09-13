package org.sscc.ssccopsserver.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.core.env.Environment;
import org.sscc.ssccopsserver.global.logging.EcsJsonEncoder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/*
 * 기동 줄이 Kibana 의 «배포 시점»으로 골라지는 모양인지 본다 (#426 · ssccops#340).
 *
 * 실제 인코더(EcsJsonEncoder)로 굳혀 필드 경로를 못 박는다 — deploy/kibana/log-schema.md 의
 * «기동» 절과 같은 값이어야 한다. git 정보가 없을 때 labels 키 자체가 없는 것도 계약이다.
 */
@DisplayName("기동 줄 — event.action · labels.git_commit")
class BuildVersionLoggerLineTest {

    private static final String SHA = "a92eed61cdeb6ffebb3e0930d9f6399eb411258f";

    private ListAppender<ILoggingEvent> captured;
    private EcsJsonEncoder encoder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        captured = new ListAppender<>();
        captured.setContext(context);
        captured.start();
        logger().addAppender(captured);
        encoder = new EcsJsonEncoder();
        encoder.setContext(context);
        encoder.setEnvironment("test");
        encoder.start();
    }

    @AfterEach
    void tearDown() {
        logger().detachAppender(captured);
        encoder.stop();
    }

    @Test
    @DisplayName("빌드·git 정보가 있으면 event.action=app.start 와 labels.git_commit 이 실린다")
    void marksDeploymentWithCommit() throws Exception {
        Properties build = new Properties();
        build.setProperty("version", "0.2.9");
        build.setProperty("time", Instant.parse("2026-09-14T12:00:00Z").toString());
        Properties git = new Properties();
        git.setProperty("commit.id", SHA);
        new BuildVersionLogger(
                        Optional.of(new BuildProperties(build)),
                        Optional.of(new GitProperties(git)),
                        environment("prod"))
                .run(null);

        JsonNode line = firstLine();
        assertThat(line.at("/event/action").asText()).isEqualTo("app.start");
        assertThat(line.at("/labels/git_commit").asText()).isEqualTo(SHA);
        assertThat(line.at("/message").asText())
                .contains("0.2.9")
                .contains("prod")
                .contains(SHA.substring(0, 7));
    }

    @Test
    @DisplayName("git 정보가 없으면 labels 키를 내지 않는다 — 빈 값을 지어내지 않는다")
    void omitsLabelsWithoutGit() throws Exception {
        new BuildVersionLogger(Optional.empty(), Optional.empty(), environment("test")).run(null);

        JsonNode line = firstLine();
        assertThat(line.at("/event/action").asText()).isEqualTo("app.start");
        assertThat(line.has("labels")).isFalse();
        assertThat(line.at("/message").asText()).contains("빌드 정보 없음");
    }

    private JsonNode firstLine() throws Exception {
        assertThat(captured.list).hasSize(1);
        return objectMapper.readTree(
                new String(encoder.encode(captured.list.get(0)), StandardCharsets.UTF_8));
    }

    private static Environment environment(String profile) {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[] {profile});
        return environment;
    }

    private static Logger logger() {
        return (Logger) LoggerFactory.getLogger(BuildVersionLogger.class);
    }
}
