package org.sscc.ssccopsserver.global.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.global.config.AppPublicBaseUrl;

/*
 * MCP 엔드포인트를 OAuth 2.0 보호 자원(RFC 9728)으로 설명하는 값 한 벌 (#384 · ADR-0026).
 *
 * MCP 클라이언트(Claude)는 401을 받으면 `WWW-Authenticate`의 `resource_metadata` URL을 열어
 * «어느 인가 서버로 가야 하는가»를 알아낸다. 그 문서가 `/.well-known/oauth-protected-resource`이고
 * 여기 담기는 것은 셋뿐이다 — 이 자원의 절대 URL(`resource`) · 인가 서버(= Supabase issuer) ·
 * 스코프 목록.
 *
 * **호스트는 요청 헤더가 아니라 `app.public-base-url`에서 온다.** mcp-server-security 라이브러리는
 * `UrlUtils.buildFullRequestUrl(request)`로 조립하는데, Coolify 프록시 뒤에서는 그 값이
 * `http://<컨테이너>:<PORT>`라 forwarded 헤더 설정이 따로 필요하고 그 설정이 빠지면 조용히 틀린
 * URL이 나간다. 이 API의 공개 주소는 이미 배포의 속성으로 갖고 있으며(#216) 새 프로퍼티를 두면
 * 같은 사실이 두 벌이 된다.
 *
 * issuer는 JWT 검증(#383)이 쓰는 `issuer-uri`와 같은 값이다 — 인가 서버로 안내하는 곳과 그 서버가
 * 발급한 토큰만 받는 곳이 갈리면 로그인은 되는데 401이 나는 상태가 된다.
 */
@Component
public class McpProtectedResource {

    /** MCP 엔드포인트 경로. `spring.ai.mcp.server.streamable-http.mcp-endpoint`와 같아야 한다. */
    public static final String MCP_PATH = "/mcp";

    public static final String METADATA_PATH = "/.well-known/oauth-protected-resource";

    /*
     * Supabase OAuth 서버가 지원하는 스코프(openid · email · profile · phone) 중 셋. phone은 뺐다 —
     * 서버가 스코프로 인가를 가르지 않으므로(ADR-0027 · 1차에는 스코프 없음) 여기 적는 것은 클라이언트가
     * 동의 화면에서 «무엇을 요청하는가»뿐이고, 연락처는 도구 출력에서도 걷어내는 값이다(ADR-0024).
     */
    private static final List<String> SCOPES_SUPPORTED = List.of("openid", "email", "profile");

    private final String resource;
    private final String metadataUrl;
    private final String authorizationServer;

    public McpProtectedResource(
            AppPublicBaseUrl appPublicBaseUrl,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri) {
        this.resource = appPublicBaseUrl.urlOf(MCP_PATH);
        // RFC 9728 §3: 자원 경로가 있으면 well-known 뒤에 그 경로를 잇는다 (…/oauth-protected-resource/mcp)
        this.metadataUrl = appPublicBaseUrl.urlOf(METADATA_PATH + MCP_PATH);
        this.authorizationServer = issuerUri;
    }

    /** `resource` — 이 MCP 서버의 절대 URL. 토큰의 대상(audience)으로 쓰일 값이기도 하다. */
    public String resource() {
        return resource;
    }

    /** 401에 실어 보내는 `WWW-Authenticate` 값. */
    public String wwwAuthenticate() {
        return "Bearer resource_metadata=\"" + metadataUrl + "\"";
    }

    /** 이 요청이 MCP 엔드포인트를 향하는가 — 401 헤더를 MCP에만 붙이기 위한 판정. */
    public boolean isMcpRequest(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (request.getContextPath() != null && !request.getContextPath().isEmpty()) {
            path = path.substring(request.getContextPath().length());
        }
        return MCP_PATH.equals(path) || path.startsWith(MCP_PATH + "/");
    }

    /** RFC 9728 문서. `ApiResponse` 봉투를 씌우지 않는다 — 스펙이 정한 모양 그대로여야 클라이언트가 읽는다. */
    public Map<String, Object> metadataDocument() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("resource", resource);
        document.put("authorization_servers", List.of(authorizationServer));
        document.put("scopes_supported", SCOPES_SUPPORTED);
        document.put("bearer_methods_supported", List.of("header"));
        document.put("resource_name", "SSCC 운영관리 MCP");
        return document;
    }
}
