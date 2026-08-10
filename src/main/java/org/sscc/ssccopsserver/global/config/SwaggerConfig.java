package org.sscc.ssccopsserver.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;

/*
 * 구글 OAuth2 로그인(/oauth2/authorization/google)과 서버 로그아웃(/logout)을 제거했으므로,
 * 이를 수동으로 문서화하던 커스텀 Path 정의도 함께 삭제했다.
 * Bearer 스킴은 Supabase Auth가 발급하는 Access Token에도 그대로 쓰이므로 유지한다.
 */
@Profile("!prod")
@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI openApi() {
        String jwtSchemeName = "JWT TOKEN";
        SecurityRequirement securityRequirement = new SecurityRequirement().addList(jwtSchemeName);
        Components components =
                new Components()
                        .addSecuritySchemes(
                                jwtSchemeName,
                                new SecurityScheme()
                                        .name(jwtSchemeName)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT"));

        return new OpenAPI()
                .addServersItem(new Server().url("/"))
                .info(apiInfo())
                .addSecurityItem(securityRequirement)
                .components(components);
    }

    private Info apiInfo() {
        return new Info()
                .title("SSCC Web BE API")
                .description("SSCC 웹서비스 API 명세서")
                .version("1.0.0");
    }
}
