package org.sscc.ssccopsserver.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/*
 * OpenAPI 스펙을 빌드 산출물로 만든다 (#412 · ssccops#342).
 *
 * `GET /v3/api-docs`를 받아 `build/openapi.json`으로 쓴다. integrate-dev.yml 의 api-compat job 이
 * base 브랜치와 PR 브랜치에서 각각 이 테스트 하나를 돌려 두 파일을 oasdiff 로 비교한다 — 응답
 * 필드 삭제·이름·타입 변경, 요청 필드 필수화, 엔드포인트·enum 값 삭제가 «깨는 변경»이고, 라벨
 * `api-breaking-approved` 없이는 머지되지 않는다. #305(서버·웹 계약 어긋남)가 이 종류였다.
 *
 * 스펙을 레포에 커밋해 diff 하는 안은 기각했다 — 매 PR 마다 손으로 갱신해야 하고 잊으면 게이트가
 * 빈다. 생성이 곧 정본이다. springdoc gradle 플러그인은 앱을 따로 띄워야 해서(DB·env) 기각.
 *
 * 컨텍스트는 공용이다(ADR-0009 — 같은 애노테이션 조합 · 스텁 jwtDecoder · MockitoBean 없음)라
 * 컨텍스트 수가 늘지 않는다. springdoc 은 prod 프로필에서만 꺼져 있으므로(application-prod.yaml)
 * test 프로필에서는 켜져 있고, /v3/api-docs 는 SecurityConfig 가 swagger 와 함께 permitAll 한다.
 *
 * 한계: 런타임 생성 스펙이라 애노테이션 누락(@Schema 없는 필드의 타입 등)은 못 잡는다 — 그것은
 * 스펙 자체의 결함이지 이 게이트의 결함이 아니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
class OpenApiSnapshotTest {

    static final Path OUTPUT = Path.of("build", "openapi.json");

    @Autowired private MockMvc mockMvc;

    @Test
    void writesOpenApiSpecToBuildDirectory() throws Exception {
        String body =
                mockMvc.perform(get("/v3/api-docs"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);

        JsonNode spec = new ObjectMapper().readTree(body);
        assertThat(spec.at("/openapi").asText()).startsWith("3.");
        // 비어 있는 스펙이 base 로 잡히면 모든 삭제가 «추가»로 보여 게이트가 조용히 빈다
        assertThat(spec.at("/paths").size()).isGreaterThan(10);
        assertThat(spec.at("/paths").has("/v1/auth/session")).isTrue();

        Files.createDirectories(OUTPUT.getParent());
        Files.writeString(OUTPUT, body, StandardCharsets.UTF_8);
        assertThat(OUTPUT).exists();
    }
}
