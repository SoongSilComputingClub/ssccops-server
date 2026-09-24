package org.sscc.ssccopsserver.global.config;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.sscc.ssccopsserver.support.TestJwtDecoderConfig;

/*
 * actuator 의 **표면**을 못 박는다 (#555 · ssccops#500).
 *
 * 이 클래스가 있는 이유는 하나다 — 2026-09-24까지 `loggers` 가 노출 목록에 있었고, 그것은 조회가
 * 아니라 `POST /actuator/loggers/{name}` 으로 **런타임 로그 레벨을 바꾸는 쓰기**였다.
 * `@RequireAuthority` 는 애노테이션 AOP 라(`RequireAuthorityAspect`) actuator 엔드포인트에 닿지
 * 않아 `anyRequest().authenticated()` 하나가 전부였고, 이 저장소는 «로그인했지만 아직 가입하지
 * 않은 사용자»를 정식 상태로 두므로 그 사람도 닿았다. dev 실측으로 GET 200(로거 1,572개) ·
 * POST 204(레벨이 실제로 바뀌었다)를 확인했다.
 *
 * **그것을 되돌리는 것은 설정 한 단어다.** 그래서 «다시 넣으면 빨개지는» 자리를 만든다 —
 * 노출 목록·익명 표면·프로브가 보는 것 셋을 여기서 본다.
 *
 * **인증된 요청으로 404 를 본다.** 익명으로 치면 401 이 오는데(시큐리티 필터체인이
 * DispatcherServlet 매핑보다 앞이라 없는 경로도 `anyRequest().authenticated()` 에 걸린다) 그
 * 401 은 «엔드포인트가 없다»와 «인증만 요구한다»를 구별하지 못한다 — 고치기 전 상태도 익명
 * 401 이었다. 토큰을 실어야 필터를 지나 매핑까지 가고, 그때 나오는 404 가 곧 «로그인한 사람에게도
 * 없다»는 증명이다. 토큰 문자열이 곧 subject 인 계약은 ADR-0009.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtDecoderConfig.class)
class ActuatorExposureTest {

    /*
     * ADR-0009 의 계약 — 토큰 문자열이 곧 subject 다. **그 값은 UUID 여야 한다** —
     * `SupabaseJwtAuthenticationConverter` 가 `sub` 로 `MemberService.findByAuthUserId(UUID)` 를
     * 부르므로 UUID 가 아니면 파싱에서 죽어 401 이 된다(아무 문자열을 넣어 그렇게 됐다).
     *
     * 가입한 적 없는 UUID 를 쓰는 것이 의도다 — 이 저장소는 «로그인했지만 아직 가입하지 않은
     * 사용자»를 정식 상태로 두고, 고치기 전의 `loggers` 에는 **바로 그 사람도 닿았다.**
     * 즉 여기서 404 가 나는 것은 «가장 약한 자격에게도 없다»는 뜻이다.
     */
    private static final String AUTHORIZATION = "Authorization";

    private static final String BEARER_ANY_MEMBER =
            "Bearer " + UUID.nameUUIDFromBytes("actuator-exposure-test".getBytes(UTF_8));

    @Autowired private MockMvc mockMvc;

    @Autowired private Environment environment;

    /*
     * 노출 목록에서 빠졌으면 매핑 자체가 없어 **401이 아니라 404**다.
     *
     * 401을 기대하면 «인증만 요구하는 상태»(= 고치기 전)도 통과하므로 이 테스트가 막으려는 것을
     * 못 막는다. 404를 기대하는 것이 곧 «엔드포인트가 없다»를 요구하는 것이다.
     */
    @Test
    void doesNotExposeLoggersEndpointEvenToAuthenticatedCaller() throws Exception {
        mockMvc.perform(get("/actuator/loggers").header(AUTHORIZATION, BEARER_ANY_MEMBER))
                .andExpect(status().isNotFound());
        mockMvc.perform(
                        post("/actuator/loggers/org.sscc")
                                .header(AUTHORIZATION, BEARER_ANY_MEMBER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"configuredLevel\":\"TRACE\"}"))
                .andExpect(status().isNotFound());
    }

    /*
     * 같은 자격으로 지표도 확인한다 — 이쪽은 **노출은 유지하고 인증만 요구한다**는 결정이라
     * 404 가 아니라 200/401 이 정상이다. 여기 두는 것은 «loggers 를 빼면서 metrics 도 함께
     * 빠뜨렸다»를 잡기 위해서다.
     */
    @Test
    void keepsMetricsExposedButAuthenticated() throws Exception {
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/metrics").header(AUTHORIZATION, BEARER_ANY_MEMBER))
                .andExpect(status().isOk());
    }

    /*
     * 익명에게 나가는 것은 **상태 코드뿐**이다.
     *
     * `show-details: when-authorized` 라 `components` 가 없어야 한다 — 그전에는
     * `db`·`diskSpace`(free·total·path·threshold)가 그대로 나갔고, 앱 DB 가 Supabase Free 라
     * «지금 DB 가 멈췄는가»를 밖에서 관찰할 수 있었다(ADR-0041).
     */
    @Test
    void probesAnswerAnonymouslyWithoutComponentDetails() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    /*
     * ⚠️ **이 테스트가 이 PR 의 핵심이다.**
     *
     * ADR-0022 가 컨테이너 헬스체크 경로로 `/actuator/health` 를 고른 근거는 «DB 를 포함한다»
     * 였다. 익명 표면을 프로브로 좁히면서 `readiness` 그룹에 `db` 를 넣지 않으면 스프링 기본값
     * (`readinessState` 하나)이 그대로 쓰여 **컨테이너는 healthy 인데 DB 가 죽은 상태**가 되고,
     * autoheal 이 재시작하지 않는다. 그 실패는 조용하다 — 헬스체크가 초록이기 때문이다.
     *
     * 그룹 구성은 인증 여부와 무관하게 `/actuator/health/readiness` 의 **집계 대상**을 정하므로,
     * 익명 응답의 `status` 만으로는 확인할 수 없다. 그래서 설정을 직접 읽어 대조한다.
     */
    @Test
    void readinessGroupIncludesDatabase() {
        String include =
                environment.getProperty("management.endpoint.health.group.readiness.include");
        Assertions.assertThat(include)
                .as("readiness 그룹이 db 를 포함해야 한다 — ADR-0022 의 «헬스체크는 DB 를" + " 포함한다»가 이 줄에 매여 있다")
                .isNotNull()
                .contains("db");
    }
}
