package org.sscc.ssccopsserver.global.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.EntityResponse;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.apipayload.code.error.CommonErrorCode;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.WebMvcStatelessServerTransport;

/*
 * MCP 전송 빈 — Streamable HTTP의 **stateless** 변종 (#384 · #393 · ADR-0027).
 *
 * **세션이 없다** (#393 · ssccops#314). 처음(#384)에는 세션 있는 `WebMvcStreamableServerTransportProvider`
 * (`protocol: STREAMABLE`)였고 세션이 메모리에 있어 재배포마다 끊기는 것을 «클라이언트가 재연결한다»고
 * 감수했는데, 2026-09-13 dev 실측은 달랐다 — Redeploy 뒤 Claude Code가 옛 `Mcp-Session-Id`로 POST →
 * 404 → 클라이언트가 «failed to connect»로 굳어 자동 재초기화가 없었다. 우리 도구는 요청마다 Bearer로
 * 신원을 받고 서버에 아무 상태도 두지 않으므로(ADR-0027) 세션이 있어야 할 이유가 처음부터 없었다.
 * `spring.ai.mcp.server.protocol=STATELESS`(Spring AI 1.1.8 `McpServerStatelessAutoConfiguration`)로
 * 바꾸면 `Mcp-Session-Id`를 내지도 요구하지도 않아 재배포와 무관하고, initialize 없이 온 `tools/call`도
 * 그대로 처리한다. 세션을 Redis 같은 외부 저장소에 두는 안은 기각 — 저장할 상태가 없다.
 *
 * 자동 구성(McpServerStatelessWebMvcAutoConfiguration)이 만드는 것과 같은 빈을 여기서 직접 만드는
 * 이유는 둘이다.
 *
 * 1. **`contextExtractor`** — 자동 구성은 요청에서 아무것도 꺼내지 않아(`McpTransportContext.EMPTY`)
 *    도구가 «누가 부르는가»를 전송 컨텍스트로는 알 수 없다. 도구는 요청의 Bearer를 그대로 실어 자기
 *    REST를 호출해야 하므로(ADR-0027) Authorization 헤더를 전송 컨텍스트에 담아 둔다.
 *    **도구 실행 스레드에 SecurityContext가 있는가**는 #384에서 실제 테스트(McpToolExecutionContextTest)
 *    로 확인했다 — 있다. Spring AI가 서블릿 환경의 SYNC 서버에 `immediateExecution(true)`를 걸어
 *    (stateless도 같다) 도구가 `POST /mcp` 요청 스레드에서 동기로 돌기 때문이다(그 조건이 없으면
 *    Reactor boundedElastic으로 넘어가 SecurityContextHolder가 비어 있다). 그래도 전송 컨텍스트에
 *    헤더를 함께 두는 것은, 그 보장이 라이브러리 내부 결정이라 버전이 오르며 바뀔 수 있고 그때 도구가
 *    조용히 401을 받기 때문이다 — 도구 쪽 클라이언트는 SecurityContext를 먼저 보고 없으면 이 컨텍스트를
 *    본다. **클라이언트 IP도 같은 자리에 담는다** (#390). 도구가 자기 REST를 `localhost`로 부르면 그
 *    안쪽 요청의 remoteAddr는 127.0.0.1이라 `AuditLog`의 `source.ip`가 언제나 루프백이었다(2026-09-13
 *    dev `subwork.transition` 실측). 원 MCP 요청의 `X-Forwarded-For`(프록시 뒤라 그것이 클라이언트,
 *    없으면 `getRemoteAddr()`)를 여기 실어 두고 `McpRestClient`가 자기 호출에 같은 이름의 헤더로 그대로
 *    싣는다 — 체인을 가공하지 않으므로 `AuditLog`가 첫 값을 고르는 규칙이 그대로 맞는다.
 *    `RequestContextHolder`로 도구 실행 시점에 꺼내는 길도 있지만 그 보장이 위와 같은 스레드 조건에
 *    매여 있고, 토큰과 달리 두 길의 값이 같아 우선순위를 둘 이유가 없어 전송 컨텍스트 하나로 둔다.
 *
 * 2. **오류 본문에서 예외 객체를 걷어낸다** — 라우터 함수(`webMvcStatelessServerRouterFunction`)를
 *    여기서 만들어 필터를 건다. MCP Java SDK의 전송은 4xx·5xx 본문에 `McpError`(RuntimeException)
 *    **객체 자체**를 싣고(`ServerResponse.body(new McpError(...))`), Spring MVC가 그것을 Jackson으로
 *    직렬화하면 `cause`·`stackTrace`·`suppressed`가 프레임 단위로 밖에 나간다 — 2026-09-13 실측의 404
 *    본문이 그것이었다(`WebMvcStreamableServerTransportProvider.handlePost` 프레임까지). stateless가
 *    되며 404 경로는 사라졌지만 같은 패턴이 잘못된 JSON-RPC 본문의 400과 핸들러 실패의 500에 남아 있다.
 *    `@ControllerAdvice`는 이 응답을 잡지 못한다(예외가 아니라 정상 반환된 ServerResponse다) — 그래서
 *    본문이 `Throwable`인 응답을 상태 코드는 그대로 두고 `ApiResponse` 오류 봉투로 바꿔 낸다. 메시지는
 *    SDK의 고정 문장(`Invalid message format` 등)이라 그대로 두고 4xx는 INVALID_BODY, 5xx는
 *    INTERNAL_SERVER_ERROR 코드다. 앱 ObjectMapper에 Throwable 믹스인을 거는 안은 기각 — 모든 응답에
 *    영향이 가고 어느 경로가 예외를 내보내는지가 코드에 남지 않는다.
 *
 * 엔드포인트는 자동 구성과 같은 프로퍼티(`spring.ai.mcp.server.streamable-http.mcp-endpoint`)에서 읽어
 * yaml의 뜻이 달라지지 않게 한다. keepAlive·disallowDelete는 세션 전용 값이라 stateless에는 없다.
 */
@Configuration
public class McpServerConfig {

    /** 전송 컨텍스트에서 Authorization 헤더 원문(`Bearer …`)을 꺼내는 키. */
    public static final String AUTHORIZATION_CONTEXT_KEY = "authorization";

    /** 전송 컨텍스트에서 원 요청의 클라이언트 IP(X-Forwarded-For 체인 원문)를 꺼내는 키. */
    public static final String FORWARDED_FOR_CONTEXT_KEY = "x-forwarded-for";

    /** 자기 호출에 실을 헤더 이름. `AuditLog`가 읽는 이름과 같아야 한다. */
    public static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";

    @Bean
    public WebMvcStatelessServerTransport webMvcStatelessServerTransport(
            @Qualifier("mcpServerObjectMapper") ObjectMapper objectMapper,
            McpServerStreamableHttpProperties serverProperties) {
        return WebMvcStatelessServerTransport.builder()
                .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                .messageEndpoint(serverProperties.getMcpEndpoint())
                .contextExtractor(
                        request -> {
                            Map<String, Object> context = new LinkedHashMap<>();
                            String authorization =
                                    request.headers().firstHeader(HttpHeaders.AUTHORIZATION);
                            if (authorization != null && !authorization.isBlank()) {
                                context.put(AUTHORIZATION_CONTEXT_KEY, authorization);
                            }
                            String forwardedFor = forwardedFor(request.servletRequest());
                            if (forwardedFor != null) {
                                context.put(FORWARDED_FOR_CONTEXT_KEY, forwardedFor);
                            }
                            return context.isEmpty()
                                    ? McpTransportContext.EMPTY
                                    : McpTransportContext.create(context);
                        })
                .build();
    }

    /*
     * 빈 이름이 자동 구성의 `@ConditionalOnMissingBean(name = "webMvcStatelessServerRouterFunction")`과
     * 같아야 자동 구성이 물러난다 — 이름을 바꾸면 라우터가 둘이 되어 필터 없는 쪽이 먼저 잡힐 수 있다.
     */
    @Bean
    public RouterFunction<ServerResponse> webMvcStatelessServerRouterFunction(
            WebMvcStatelessServerTransport transport) {
        return transport.getRouterFunction().filter(McpServerConfig::withoutThrowableBody);
    }

    static ServerResponse withoutThrowableBody(
            ServerRequest request, HandlerFunction<ServerResponse> next) throws Exception {
        ServerResponse response = next.handle(request);
        if (response instanceof EntityResponse<?> entity
                && entity.entity() instanceof Throwable error) {
            CommonErrorCode code =
                    response.statusCode().is5xxServerError()
                            ? CommonErrorCode.INTERNAL_SERVER_ERROR
                            : CommonErrorCode.INVALID_BODY;
            String message = error.getMessage() == null ? code.getMessage() : error.getMessage();
            return ServerResponse.status(response.statusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ApiResponse.fail(code, message));
        }
        return response;
    }

    /*
     * 원 요청이 프록시를 지나왔으면 X-Forwarded-For 체인 그대로, 아니면 remoteAddr. 첫 값을 골라내지
     * 않는 것은 그 판정이 AuditLog 한 곳에 있어야 해서다 — 여기서 자르면 규칙이 두 벌이 된다.
     */
    static String forwardedFor(HttpServletRequest request) {
        String forwarded = request.getHeader(FORWARDED_FOR_HEADER);
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded;
        }
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? null : remote;
    }
}
