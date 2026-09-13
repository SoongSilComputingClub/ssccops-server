package org.sscc.ssccopsserver.global.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

/*
 * /actuator/info 의 모양 (#410 · ssccops#340).
 *
 * deploy-history.yml 이 배포 뒤 이 응답의 `git.commit.id.full`(없으면 `abbrev`)을 푸시된 sha 와
 * 비교해 «정말 이 커밋이 떠 있는가»를 판정한다. 여기서 경로가 바뀌면 모든 레코드가 조용히
 * `unverified` 가 되므로 JSON 경로를 테스트로 못 박는다 — `management.info.git.mode: full` 이
 * 아니면 `commit.id` 가 문자열 하나로 뭉개져 `.full` 이 없다.
 *
 * git.properties 는 generateGitProperties 태스크가 `classes` 앞에 만든다(로컬·CI 모두 `.git` 이
 * 있다). 인증 없이 부르는 것은 SecurityConfig 가 /actuator/info 를 permitAll 로 열어 두었기
 * 때문이며, 워크플로도 토큰 없이 부른다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
class ActuatorInfoTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void exposesFullGitCommitIdWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.git.commit.id.full", Matchers.matchesRegex("[0-9a-f]{40}")))
                .andExpect(
                        jsonPath("$.git.commit.id.abbrev", Matchers.matchesRegex("[0-9a-f]{7,}")))
                .andExpect(jsonPath("$.git.branch").isString())
                .andExpect(jsonPath("$.build.version").isString());
    }
}
