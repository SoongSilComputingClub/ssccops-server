package org.sscc.ssccopsserver.global.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMemberArgumentResolver;

import lombok.RequiredArgsConstructor;

/*
 * Spring MVC 확장 지점.
 *
 * 주의: CORS는 SecurityConfig.corsConfigurationSource() 한 곳에서만 정의한다.
 * 시큐리티 필터체인이 먼저 처리하므로 여기에 addCorsMappings()를 추가하면 설정이 이중화된다.
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final CurrentMemberArgumentResolver currentMemberArgumentResolver;

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentMemberArgumentResolver);
    }
}
