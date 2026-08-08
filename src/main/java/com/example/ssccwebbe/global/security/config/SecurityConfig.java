package com.example.ssccwebbe.global.security.config;

import java.util.List;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Qualifier;
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
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.logout.LogoutFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.example.ssccwebbe.global.security.UserRoleType;
import com.example.ssccwebbe.global.security.handler.CustomLogoutSuccessHandler;
import com.example.ssccwebbe.global.security.handler.RefreshTokenLogoutHandler;
import com.example.ssccwebbe.global.security.jwt.filter.JwtFilter;
import com.example.ssccwebbe.global.security.jwt.service.JwtService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
@EnableWebSecurity // 시큐리티 빈 설정 활성화
public class SecurityConfig {

    private final AuthenticationSuccessHandler socialSuccessHandler;
    private final AuthenticationFailureHandler socialFailureHandler;
    private final AuthenticationEntryPoint authenticationEntryPoint;
    private final AccessDeniedHandler accessDeniedHandler;
    private final JwtService jwtService;
    private final CustomLogoutSuccessHandler customLogoutSuccessHandler;

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

    // LoginSuccessHandler 빈을 명확히 주입 받기 위해 Qualifier 설정 도입
    public SecurityConfig(
            @Qualifier("SocialSuccessHandler") AuthenticationSuccessHandler socialSuccessHandler,
            @Qualifier("SocialFailureHandler") AuthenticationFailureHandler socialFailureHandler,
            AuthenticationEntryPoint authenticationEntryPoint,
            AccessDeniedHandler accessDeniedHandler,
            JwtService jwtService,
            CustomLogoutSuccessHandler customLogoutSuccessHandler) {
        this.socialSuccessHandler = socialSuccessHandler;
        this.socialFailureHandler = socialFailureHandler;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.jwtService = jwtService;
        this.customLogoutSuccessHandler = customLogoutSuccessHandler;
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
        configuration.setExposedHeaders(List.of("Authorization", "Set-Cookie"));
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

        // 기본 로그아웃 필터 + 커스텀 Refresh 토큰 삭제 핸들러 추가 + 로그아웃 성공 핸들러
        http.logout(
                logout ->
                        logout.addLogoutHandler(new RefreshTokenLogoutHandler(jwtService))
                                .logoutSuccessHandler(customLogoutSuccessHandler));

        // 기본 Form 기반 인증 필터들 disable => 때문에 LoginFilter.java를 등록하여 사용해야함
        http.formLogin(AbstractHttpConfigurer::disable);

        // 기본 Basic 인증 필터 disable
        http.httpBasic(AbstractHttpConfigurer::disable);

        // OAuth2 인증용
        http.oauth2Login(
                oauth2 ->
                        oauth2.successHandler(socialSuccessHandler)
                                .failureHandler(socialFailureHandler));

        // 인가
        http.authorizeHttpRequests(
                auth -> {
                    if (swaggerEnabled) {
                        auth.requestMatchers(
                                        "/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html")
                                .permitAll(); // Swagger UI : 비 prod 환경에서만 허용
                    }
                    auth.requestMatchers("/jwt/exchange", "/jwt/refresh")
                            .permitAll() // JWT 발급 경로 : 전체 허용
                            .requestMatchers("/admin/**")
                            .hasRole(UserRoleType.ADMIN.name())
                            .anyRequest()
                            .authenticated();
                });

        // 예외 처리
        http.exceptionHandling(
                e ->
                        e.authenticationEntryPoint(authenticationEntryPoint)
                                .accessDeniedHandler(accessDeniedHandler));

        // 커스텀 필터 추가 (로그아웃 필터 앞에 넣음)
        http.addFilterBefore(new JwtFilter(), LogoutFilter.class);

        // 세션 필터 설정 (STATELESS)
        http.sessionManagement(
                session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        return http.build();
    }
}
