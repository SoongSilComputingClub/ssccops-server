package org.sscc.ssccopsserver.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 규정 도우미 통합 테스트가 공유하는 스텁 (#396).
 *
 * <p><b>한 벌을 함께 import 하는 것이 요점이다.</b> {@code TestJwtDecoderConfig}(ADR-0009)와 같은 모양이며, 클래스마다
 * {@code @MockitoBean}을 두면 그만큼 스프링 컨텍스트가 갈린다 — #103이 58개를 25개로 줄여 놓은 이득을 되돌리는 방향이다.
 *
 * <p>운영에서 이 자리를 채우는 것은 {@code PgVectorRagChunkStore}이고 그 빈은 Gemini 키가 있을 때만 선다({@code
 * AssistantConfig}). 그래서 {@code test} 프로필에는 경쟁할 빈이 없다 — {@code @Primary}가 필요 없는 이유다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class AssistantStubConfig {

    @Bean
    InMemoryRagChunkStore ragChunkStore() {
        return new InMemoryRagChunkStore();
    }
}
