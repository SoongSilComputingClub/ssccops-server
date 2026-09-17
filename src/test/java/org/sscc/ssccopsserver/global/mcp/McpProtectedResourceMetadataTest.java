package org.sscc.ssccopsserver.global.mcp;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.sscc.ssccopsserver.global.apipayload.code.error.CommonErrorCode;
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

/**
 * OAuth 보호 자원 메타데이터와 MCP 401 계약(#384 · RFC 9728 · ADR-0026).
 *
 * <p>호스트는 요청이 아니라 {@code app.public-base-url}(test 프로필: https://api.test.local)에서 온다 — MockMvc의 요청
 * 호스트는 localhost이므로 문서의 URL이 그것과 다르다는 것이 곧 검증이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
class McpProtectedResourceMetadataTest {

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("메타데이터 문서는 익명으로 열리고 자원·인가 서버를 설정값으로 말한다")
    void metadataIsAnonymousAndBuiltFromConfig() throws Exception {
        mockMvc.perform(get("/.well-known/oauth-protected-resource/mcp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resource").value("https://api.test.local/mcp"))
                .andExpect(jsonPath("$.authorization_servers[0]").value("http://localhost/auth/v1"))
                .andExpect(jsonPath("$.scopes_supported[0]").value("openid"))
                .andExpect(jsonPath("$.bearer_methods_supported[0]").value("header"))
                // ApiResponse 봉투가 아니다
                .andExpect(jsonPath("$.success").doesNotExist());
    }

    @Test
    @DisplayName("루트 well-known 경로도 같은 문서를 준다")
    void rootWellKnownPathServesSameDocument() throws Exception {
        mockMvc.perform(get("/.well-known/oauth-protected-resource"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resource").value("https://api.test.local/mcp"));
    }

    @Test
    @DisplayName("/mcp 미인증 401에는 resource_metadata가 붙고 본문은 ApiResponse다")
    void mcpUnauthenticatedCarriesResourceMetadata() throws Exception {
        mockMvc.perform(
                        post("/mcp")
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                                .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(
                        header().string(
                                        HttpHeaders.WWW_AUTHENTICATE,
                                        "Bearer"
                                            + " resource_metadata=\"https://api.test.local/.well-known/oauth-protected-resource/mcp\""))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(CommonErrorCode.UNAUTHORIZED.getCode()));
    }

    @Test
    @DisplayName("다른 경로의 401은 그대로다 — 헤더가 붙지 않는다")
    void otherPathsKeepThe401Contract() throws Exception {
        mockMvc.perform(get("/v1/auth/session"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andExpect(jsonPath("$.code").value(CommonErrorCode.UNAUTHORIZED.getCode()));
    }
}
