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
import org.sscc.ssccopsserver.global.security.jwt.SupabaseJwtAuthenticationConverter;

import lombok.extern.slf4j.Slf4j;

/*
 * Supabase Auth가 발급한 JWT를 검증하는 리소스 서버 필터체인.
 *
 * SupabaseJwtAuthenticationConverter가 JWT의 sub/email로 MemberEntity를 찾거나 임시회원으로
 * 프로비저닝해 인증 주체로 설정한다. 아직 권한(GrantedAuthority)을 채우지 않으므로 hasRole 기반
 * 인가(/admin/** 등)는 role 판별 로직이 붙기 전까지 사실상 항상 거부된다 — 별도 이슈에서 처리한다.
 */
@Slf4j
@Configuration
@EnableWebSecurity // 시큐리티 빈 설정 활성화
public class SecurityConfig {

    private final AuthenticationEntryPoint authenticationEntryPoint;
    private final AccessDeniedHandler accessDeniedHandler;
    private final SupabaseJwtAuthenticationConverter supabaseJwtAuthenticationConverter;

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
            AccessDeniedHandler accessDeniedHandler,
            SupabaseJwtAuthenticationConverter supabaseJwtAuthenticationConverter) {
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.supabaseJwtAuthenticationConverter = supabaseJwtAuthenticationConverter;
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

        // Supabase JWT 검증 (JWKS) 및 인증 컨텍스트 구성
        http.oauth2ResourceServer(
                oauth2 ->
                        oauth2.jwt(
                                jwt ->
                                        jwt.jwtAuthenticationConverter(
                                                supabaseJwtAuthenticationConverter)));

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
