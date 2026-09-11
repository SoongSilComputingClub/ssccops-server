package org.sscc.ssccopsserver.global.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.logging.EcsJsonEncoder;
import org.sscc.ssccopsserver.global.security.AuthenticatedUser;
import org.sscc.ssccopsserver.global.security.jwt.SupabaseAuthenticationToken;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/*
 * 감사 로그 한 줄의 모양과 기록 시점 (ssccops#299 · ADR-0024).
 *
 * AUDIT 로거에 ListAppender를 달아 잡고, 실제 인코더(EcsJsonEncoder)로 굳혀 JSON을 본다 —
 * Logstash·Kibana가 보는 것이 그 JSON이다.
 */
class AuditLogTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AuditLog auditLog = new AuditLog();
    private ListAppender<ILoggingEvent> captured;
    private EcsJsonEncoder encoder;

    @BeforeEach
    void setUp() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        captured = new ListAppender<>();
        captured.setContext(context);
        captured.start();
        ((Logger) LoggerFactory.getLogger(AuditLog.LOGGER_NAME)).addAppender(captured);

        encoder = new EcsJsonEncoder();
        encoder.setContext(context);
        encoder.setEnvironment("test");
        encoder.start();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.9");
        request.addHeader("X-Forwarded-For", "203.0.113.10, 10.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(AuditLog.LOGGER_NAME)).detachAppender(captured);
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        encoder.stop();
    }

    @Test
    void writesEcsAuditLine() throws Exception {
        authenticateAsMember(42L);

        auditLog.record(
                AuditEvent.success(AuditAction.MEMBER_GRADE_CHANGE)
                        .target(7L)
                        .change("TEMP", "REGULAR")
                        .message("Member grade changed")
                        .build());

        JsonNode json = onlyLine();
        assertThat(json.at("/event/dataset").asText()).isEqualTo("ssccops.audit");
        assertThat(json.at("/event/action").asText()).isEqualTo("member.grade.change");
        assertThat(json.at("/event/outcome").asText()).isEqualTo("success");
        assertThat(json.at("/user/id").asText()).isEqualTo("42");
        assertThat(json.at("/source/ip").asText()).isEqualTo("203.0.113.10");
        assertThat(json.at("/audit/target/type").asText()).isEqualTo("member");
        assertThat(json.at("/audit/target/id").asText()).isEqualTo("7");
        assertThat(json.at("/audit/change/before").asText()).isEqualTo("TEMP");
        assertThat(json.at("/audit/change/after").asText()).isEqualTo("REGULAR");
        assertThat(json.get("message").asText()).isEqualTo("Member grade changed");
        assertThat(json.has("error")).isFalse();
    }

    @Test
    void failureCarriesErrorCodeAndIsWrittenImmediatelyEvenInTransaction() throws Exception {
        authenticateAsMember(1L);
        TransactionSynchronizationManager.initSynchronization();

        auditLog.record(
                AuditEvent.failure(AuditAction.MEMBER_DELETE, "MEMBER_REFERENCED")
                        .target(9L)
                        .decision("폼 작성자")
                        .build());

        // 트랜잭션 안인데도 커밋을 기다리지 않는다 — 실패는 롤백으로 끝나 커밋이 오지 않는다
        JsonNode json = onlyLine();
        assertThat(json.at("/event/outcome").asText()).isEqualTo("failure");
        assertThat(json.at("/error/code").asText()).isEqualTo("MEMBER_REFERENCED");
        assertThat(json.at("/audit/decision").asText()).isEqualTo("폼 작성자");
    }

    /* 성공은 커밋 뒤에만 남는다 — 롤백된 변경을 «성공»으로 남기지 않는다 */
    @Test
    void successWaitsForCommit() throws Exception {
        authenticateAsMember(1L);
        TransactionSynchronizationManager.initSynchronization();

        auditLog.record(AuditEvent.success(AuditAction.MEMBER_ROLE_GRANT).target(3L).build());
        assertThat(captured.list).isEmpty();

        for (TransactionSynchronization sync :
                TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }
        assertThat(onlyLine().at("/event/action").asText()).isEqualTo("member.role.grant");
    }

    /* 미가입 주체는 회원 id가 없다 — auth user id로 남기고 그 사실을 표시한다 */
    @Test
    void unsignedActorUsesAuthUserId() throws Exception {
        UUID authUserId = UUID.randomUUID();
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new SupabaseAuthenticationToken(
                                new AuthenticatedUser(authUserId, null, null, "google", null),
                                null));

        auditLog.record(AuditEvent.success(AuditAction.MEMBER_SIGNUP).target(5L).build());

        JsonNode json = onlyLine();
        assertThat(json.at("/user/id").asText()).isEqualTo(authUserId.toString());
        assertThat(json.at("/user/signed_up").asBoolean()).isFalse();
    }

    /*
     * 개인정보가 실릴 자리가 없다 — 프로필 수정은 바뀐 필드 이름만 남긴다. 이 테스트가 2차 방어다:
     * 누가 나중에 값을 실어 보내는 인자를 열면 여기서 빨개진다.
     */
    @Test
    void profileUpdateCarriesFieldNamesOnly() throws Exception {
        authenticateAsMember(1L);

        auditLog.record(
                AuditEvent.success(AuditAction.MEMBER_PROFILE_UPDATE)
                        .target(8L)
                        .changedFields(List.of("PHONE_NUMBER", "EMAIL"))
                        .build());

        String line = encode(captured.list.get(0));
        assertThat(line).contains("\"changed_fields\":[\"PHONE_NUMBER\",\"EMAIL\"]");
        // 전화번호·이메일 모양이 어디에도 없다 ("@timestamp"의 @는 이메일이 아니다)
        assertThat(line).doesNotContain("010-").doesNotContainPattern("[\\w.]+@[\\w.]+\\.[a-z]+");
    }

    private void authenticateAsMember(Long memberId) {
        MemberEntity member = org.mockito.Mockito.mock(MemberEntity.class);
        org.mockito.Mockito.when(member.getId()).thenReturn(memberId);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new SupabaseAuthenticationToken(
                                new AuthenticatedUser(
                                        UUID.randomUUID(), null, null, "google", member),
                                null));
    }

    private JsonNode onlyLine() throws Exception {
        assertThat(captured.list).hasSize(1);
        return mapper.readTree(encode(captured.list.get(0)));
    }

    private String encode(ILoggingEvent event) {
        return new String(encoder.encode(event), StandardCharsets.UTF_8);
    }
}
