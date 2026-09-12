package org.sscc.ssccopsserver.global.mcp;

import java.util.Map;

import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider;

/*
 * MCP Streamable HTTP 전송 빈 (#384 · ADR-0027).
 *
 * Spring AI 자동 구성(McpServerStreamableHttpWebMvcAutoConfiguration)이 만드는 것과 같은 빈인데
 * 여기서 직접 만드는 이유는 하나 — **`contextExtractor`**. 자동 구성은 요청에서 아무것도 꺼내지
 * 않아(`McpTransportContext.EMPTY`) 도구가 «누가 부르는가»를 전송 컨텍스트로는 알 수 없다.
 * 도구는 요청의 Bearer를 그대로 실어 자기 REST를 호출해야 하므로(ADR-0027) Authorization 헤더를
 * 전송 컨텍스트에 담아 둔다.
 *
 * **도구 실행 스레드에 SecurityContext가 있는가**는 #384에서 실제 테스트(McpToolExecutionContextTest)
 * 로 확인했다 — 있다. Spring AI가 SERVLET+SYNC 조합에 `immediateExecution(true)`를 걸어 도구가
 * `POST /mcp` 요청 스레드에서 동기로 돌기 때문이다(그 조건이 없으면 Reactor boundedElastic으로
 * 넘어가 SecurityContextHolder가 비어 있다). 그래도 전송 컨텍스트에 헤더를 함께 두는 것은,
 * 그 보장이 라이브러리 내부 결정이라 버전이 오르며 바뀔 수 있고 그때 도구가 조용히 401을 받기
 * 때문이다 — 도구 쪽 클라이언트는 SecurityContext를 먼저 보고 없으면 이 컨텍스트를 본다.
 *
 * 자동 구성의 나머지 값(엔드포인트 · keepAlive · disallowDelete)은 같은 프로퍼티에서 그대로 읽어
 * yaml의 뜻이 달라지지 않게 한다.
 */
@Configuration
public class McpServerConfig {

    /** 전송 컨텍스트에서 Authorization 헤더 원문(`Bearer …`)을 꺼내는 키. */
    public static final String AUTHORIZATION_CONTEXT_KEY = "authorization";

    @Bean
    public WebMvcStreamableServerTransportProvider webMvcStreamableServerTransportProvider(
            @Qualifier("mcpServerObjectMapper") ObjectMapper objectMapper,
            McpServerStreamableHttpProperties serverProperties) {
        return WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                .mcpEndpoint(serverProperties.getMcpEndpoint())
                .keepAliveInterval(serverProperties.getKeepAliveInterval())
                .disallowDelete(serverProperties.isDisallowDelete())
                .contextExtractor(
                        request -> {
                            String authorization =
                                    request.headers().firstHeader(HttpHeaders.AUTHORIZATION);
                            if (authorization == null || authorization.isBlank()) {
                                return McpTransportContext.EMPTY;
                            }
                            return McpTransportContext.create(
                                    Map.of(AUTHORIZATION_CONTEXT_KEY, authorization));
                        })
                .build();
    }
}
