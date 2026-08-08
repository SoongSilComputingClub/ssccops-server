package com.example.ssccwebbe.global.security.config;

import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.ssccwebbe.domain.user.repository.UserRefreshRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ScheduleConfig {
    private final UserRefreshRepository userRefreshRepository;

    @Value("${refresh-token.ttl-days}")
    private int refreshTokenTtlDays; // 리프레시 토큰 TTL 설정

    // 매일 지정시간 마다 TTL 만료 Refresh 토큰 삭제
    @Scheduled(cron = "${refresh-token.cleanup-cron}")
    @Transactional
    public void refreshEntityTtlSchedule() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(refreshTokenTtlDays);
        userRefreshRepository.deleteByCreatedDateBefore(cutoff);
    }
}
