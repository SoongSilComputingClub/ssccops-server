package org.sscc.ssccopsserver.global.security.config;

import java.util.List;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.sscc.ssccopsserver.global.security.UserRoleType;

import lombok.extern.slf4j.Slf4j;

/*
 * 인증 수단이 없는 상태의 필터체인.
 *
 * 구글 OAuth2 로그인과 자체 JWT 발급 스택을 제거했으므로, 현재 이 서버는 요청을 인증할 방법이 없다.
 * permitAll로 열어둔 Swagger를 제외한 모든 요청은 CustomAuthenticationEntryPoint를 타고 401로 응답한다.
 *
 * 다음 단계에서 Supabase Auth가 발급한 JWT를 검증하는 resource server 설정
 * (spring-boot-starter-oauth2-resource-server + JWKS)을 여기에 붙인다.
 * 인가 규칙(RoleHierarchy, /admin/** 제한)은 그대로 재사용된다.
 */
@Slf4j
@Configuration
@EnableWebSecurity // 시큐리티 빈 설정 활성화
public class SecurityConfig {

    private final AuthenticationEntryPoint authenticationEntryPoint;
    private final AccessDeniedHandler accessDeniedHandler;

    @Value("${frontend.url}")
    private String frontendUrl;

    @Value("${springdoc.swagger-ui.enabled:true}")
    private boolean swaggerEnabled;

    @Value("${spring.profiles.active:default}")
    private String activeProfile;

    @PostConstruct
    public void checkConfig() {
        log.info("Active profile: {}", activeProfile);
        log.info("Swagger UI enabled: {}", swaggerEnabled);
    }

    public SecurityConfig(
            AuthenticationEntryPoint authenticationEntryPoint,
            AccessDeniedHandler accessDeniedHandler) {
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    // 권한 계층
    @Bean
    public RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.withRolePrefix("ROLE_")
                .role(UserRoleType.ADMIN.name())
                .implies(UserRoleType.USER.name())
                .role(UserRoleType.USER.name())
                .implies(UserRoleType.PREUSER.name())
                .build();
    }

    // CORS 빈 등록
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(frontendUrl));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        configuration.setExposedHeaders(List.of("Authorization"));
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    // 시큐리티 필터체인 설정
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        // CSRF 보안 필터 disable(stateless 서버이기에 불필요함)
        http.csrf(AbstractHttpConfigurer::disable);

        // CORS 설정 (리액트 기반 서비스이기에 필수적)
        http.cors(cors -> cors.configurationSource(corsConfigurationSource()));

        // 기본 Form 기반 인증 필터들 disable
        http.formLogin(AbstractHttpConfigurer::disable);

        // 기본 Basic 인증 필터 disable
        http.httpBasic(AbstractHttpConfigurer::disable);

        // 로그아웃은 Supabase(클라이언트) 책임이므로 서버 로그아웃 필터를 비활성화한다
        http.logout(AbstractHttpConfigurer::disable);

        // 인가
        http.authorizeHttpRequests(
                auth -> {
                    if (swaggerEnabled) {
                        auth.requestMatchers(
                                        "/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html")
                                .permitAll(); // Swagger UI : 비 prod 환경에서만 허용
                    }
                    auth.requestMatchers("/admin/**")
                            .hasRole(UserRoleType.ADMIN.name())
                            .anyRequest()
                            .authenticated();
                });

        // 예외 처리
        http.exceptionHandling(
                e ->
                        e.authenticationEntryPoint(authenticationEntryPoint)
                                .accessDeniedHandler(accessDeniedHandler));

        // 세션 필터 설정 (STATELESS)
        http.sessionManagement(
                session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        return http.build();
    }
}
