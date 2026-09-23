package org.sscc.ssccopsserver.global.crawler;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

/**
 * 크롤러 규칙 (#541 · ssccops#482).
 *
 * <p>permitAll을 더하는 변경이라 <b>연 것과 닫힌 채로 남은 것을 함께</b> 잰다 — 이 파일이 지키는 것은 "robots.txt가 열렸다"뿐 아니라 "그것
 * 말고는 아무것도 열리지 않았다"이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
class RobotsTxtTest {

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("robots.txt는 익명으로 열리고 평문 그대로 나간다")
    void robotsIsAnonymousPlainText() throws Exception {
        mockMvc.perform(get(RobotsTxt.PATH))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                // ApiResponse 봉투가 아니다 — 크롤러가 읽는 것은 평문 그 자체다
                .andExpect(content().string(RobotsTxt.BODY));
    }

    @Test
    @DisplayName("전부 닫고 이미지 두 갈래만 연다")
    void closesEverythingButImages() throws Exception {
        mockMvc.perform(get(RobotsTxt.PATH))
                .andExpect(content().string(containsString("Disallow: /")))
                .andExpect(content().string(containsString("Allow: /public/v1/events/*/images/")))
                .andExpect(content().string(containsString("Allow: /public/v1/posts/*/images/")));
    }

    @Test
    @DisplayName("robots.txt를 열었다고 다른 경로가 함께 열리지 않는다")
    void otherPathsStayAuthenticated() throws Exception {
        mockMvc.perform(get("/v1/notifications")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/v1/works")).andExpect(status().isUnauthorized());
    }
}
