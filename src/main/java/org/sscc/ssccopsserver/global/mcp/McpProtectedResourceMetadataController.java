package org.sscc.ssccopsserver.global.mcp;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/*
 * `/.well-known/oauth-protected-resource` — MCP 클라이언트가 401 뒤에 여는 문서 (#384 · RFC 9728).
 *
 * **익명 경로다.** SecurityConfig가 이 접두사를 permitAll로 연다 — 서비스 데이터가 아니라 «어느
 * 인가 서버로 가라»는 메타데이터라 `/public/v1` 규칙(업무 API 중 익명 접근은 그 접두사뿐)과 갈리지
 * 않는다. 실리는 값은 전부 설정에서 온 상수이고 요청에 따라 달라지지 않는다.
 *
 * 두 경로를 다 받는다: 루트(`/.well-known/oauth-protected-resource`)와 자원 경로가 붙은
 * 것(`…/mcp`). 우리는 401 헤더에 후자를 정확히 적어 보내지만, 스펙은 클라이언트가 헤더 없이 자원
 * URL에서 유도하는 경로도 허용하며 그 유도가 루트로 떨어지는 구현이 있다.
 *
 * `ApiResponse`로 감싸지 않는다 — 스펙 문서는 봉투 없이 그 모양 그대로여야 한다.
 */
@RestController
@RequiredArgsConstructor
public class McpProtectedResourceMetadataController {

    private final McpProtectedResource protectedResource;

    @GetMapping({
        McpProtectedResource.METADATA_PATH,
        McpProtectedResource.METADATA_PATH + McpProtectedResource.MCP_PATH
    })
    public Map<String, Object> metadata() {
        return protectedResource.metadataDocument();
    }
}
