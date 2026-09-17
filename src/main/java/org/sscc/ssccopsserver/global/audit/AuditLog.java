package org.sscc.ssccopsserver.global.audit;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.sscc.ssccopsserver.global.security.AuthenticatedUser;

/*
 * 감사 로그를 남기는 유일한 자리 (ssccops#299 · ADR-0024).
 *
 * 서비스가 `auditLog.record(AuditEvent.success(MEMBER_GRADE_CHANGE).target(id).change(a, b).build())`
 * 를 부른다. SLF4J Logger를 직접 쓰지 않는 것은 필드 이름이 자리마다 달라지기 때문이다 — 여기가
 * `event.dataset`·`user.id`·`source.ip`를 채우고 나머지를 StructuredArguments로 싣는다. 인코더
 * (EcsJsonEncoder)가 그 객체들을 중첩 JSON으로 내고 Logstash가 `event.dataset`으로 감사 stream에
 * 넣는다.
 *
 * **기록 시점** — 성공은 트랜잭션이 있으면 커밋 뒤, 실패는 즉시. 롤백된 변경이 «성공»으로 남으면
 * 감사 로그가 거짓말을 한다. 실패(409·403)는 예외로 나가는 길이라 트랜잭션이 롤백되므로 커밋
 * 뒤에 쓰면 영영 안 쓰인다 — 그래서 즉시다.
 *
 * **행위자·IP는 부르는 쪽이 넘기지 않는다.** SecurityContext와 현재 요청에서 여기가 읽는다. 넘겨
 * 받으면 «누가 했는가»를 부르는 쪽이 적어 넣을 수 있다(#78이 변경자를 본문으로 받지 않는 것과
 * 같은 이유). 미가입 주체는 회원 id가 없으므로 auth user id를 쓴다.
 *
 * **로깅이 업무를 막지 않는다.** 여기서 나는 예외는 삼키고 WARN 한 줄만 남긴다.
 */
@Component
public class AuditLog {

    /** 전용 로거. 레벨을 따로 조절할 수 있고 어느 클래스에서 불렀든 같은 이름으로 남는다 */
    public static final String LOGGER_NAME = "AUDIT";

    static final String DATASET = "ssccops.audit";

    private static final Logger audit = LoggerFactory.getLogger(LOGGER_NAME);
    private static final String CLIENT_ID_CLAIM = "client_id";
    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);

    public void record(AuditEvent event) {
        try {
            // 행위자·IP는 지금 스레드에서 읽어 둔다 — 커밋 뒤에는 요청 컨텍스트가 없을 수 있다
            Map<String, Object> user = actor();
            Map<String, Object> source = source();
            Map<String, Object> client = channel();
            if (event.outcome() == AuditEvent.Outcome.SUCCESS
                    && TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                write(event, user, source, client);
                            }
                        });
                return;
            }
            write(event, user, source, client);
        } catch (RuntimeException ex) {
            log.warn("감사 로그를 남기지 못했다: {} — {}", event.action().code(), ex.toString());
        }
    }

    private void write(
            AuditEvent event,
            Map<String, Object> user,
            Map<String, Object> source,
            Map<String, Object> client) {
        try {
            Map<String, Object> ecsEvent = new LinkedHashMap<>();
            ecsEvent.put("dataset", DATASET);
            ecsEvent.put("action", event.action().code());
            ecsEvent.put("outcome", event.outcome().code());

            Map<String, Object> auditFields = new LinkedHashMap<>();
            Map<String, Object> target = new LinkedHashMap<>();
            target.put("type", event.targetType());
            putIfPresent(target, "id", event.targetId());
            auditFields.put("target", target);
            if (!event.changedFields().isEmpty()) {
                auditFields.put("changed_fields", event.changedFields());
            }
            if (event.before() != null || event.after() != null) {
                Map<String, Object> change = new LinkedHashMap<>();
                putIfPresent(change, "before", event.before());
                putIfPresent(change, "after", event.after());
                auditFields.put("change", change);
            }
            putIfPresent(auditFields, "decision", event.decision());

            String message = event.message() != null ? event.message() : event.action().code();
            List<Object> arguments = new ArrayList<>();
            arguments.add(kv("event", ecsEvent));
            arguments.add(kv("user", user));
            if (client != null) {
                arguments.add(kv("client", client));
            }
            arguments.add(kv("source", source));
            arguments.add(kv("audit", auditFields));
            if (event.errorCode() != null) {
                arguments.add(kv("error", Map.of("code", event.errorCode())));
            }
            audit.info(message, arguments.toArray());
        } catch (RuntimeException ex) {
            log.warn("감사 로그를 남기지 못했다: {} — {}", event.action().code(), ex.toString());
        }
    }

    private static Map<String, Object> actor() {
        Map<String, Object> user = new LinkedHashMap<>();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof AuthenticatedUser principal) {
            if (principal.isSignedUp()) {
                user.put("id", String.valueOf(principal.member().getId()));
            } else {
                user.put("id", String.valueOf(principal.authUserId()));
                user.put("signed_up", false);
            }
        } else {
            // 요청 밖(기동 시드·배치)에서 부른 경우. 비우지 않고 «system»으로 남겨 필터가 된다
            user.put("id", "system");
        }
        return user;
    }

    /*
     * 채널 — 이 행위가 어느 클라이언트로 들어왔는가 (#384 · ADR-0026). Supabase OAuth 2.1 서버가
     * 발급한 토큰(MCP · Claude)에는 `client_id` 클레임이 있고 웹 로그인 토큰에는 없다. 같은 회원이
     * 같은 일을 웹에서 했는지 Claude가 했는지를 이것으로 가른다. 없으면 필드 자체를 내지 않는다 —
     * «웹»이라는 값을 지어내면 그것이 두 번째 사실이 된다. 값은 OAuth client id(식별자)뿐이고
     * 토큰·시크릿은 싣지 않는다(ADR-0024). 나중에 API Key가 생기면 같은 `client` 객체에
     * `api_key_id`가 들어갈 자리다.
     */
    private static Map<String, Object> channel() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getCredentials() instanceof Jwt jwt)) {
            return null;
        }
        String clientId = jwt.getClaimAsString(CLIENT_ID_CLAIM);
        if (clientId == null || clientId.isBlank()) {
            return null;
        }
        Map<String, Object> client = new LinkedHashMap<>();
        client.put("id", clientId);
        return client;
    }

    private static Map<String, Object> source() {
        Map<String, Object> source = new LinkedHashMap<>();
        if (RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            // 프록시(Traefik·Cloudflare) 뒤라 remoteAddr는 프록시다. 첫 X-Forwarded-For가 클라이언트
            String forwarded = request.getHeader("X-Forwarded-For");
            String ip =
                    forwarded != null && !forwarded.isBlank()
                            ? forwarded.split(",")[0].trim()
                            : request.getRemoteAddr();
            putIfPresent(source, "ip", ip);
        }
        return source;
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
