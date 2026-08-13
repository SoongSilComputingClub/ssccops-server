package org.sscc.ssccopsserver.global.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/*
 * 현재 시각을 빈으로 주입해 쓰기 위한 설정. Instant.now()를 코드에 직접 박으면
 * 마감 경과 판정 같은 시각 의존 규칙을 테스트에서 고정할 수 없다.
 *
 * 서비스 표준 시간대는 Asia/Seoul이다 (AP-12 — 응답 일시도 같은 오프셋으로 내린다).
 */
@Configuration
public class ClockConfig {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    @Bean
    public Clock clock() {
        return Clock.system(SERVICE_ZONE);
    }
}
