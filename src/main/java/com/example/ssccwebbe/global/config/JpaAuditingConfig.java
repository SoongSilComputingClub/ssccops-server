package com.example.ssccwebbe.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
    // createdDate 와 updatedDate 갱신을 자동화 하기 위한 설정
}
