package org.sscc.ssccopsserver.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/*
 * `@Scheduled`의 문을 연다 (#487 · ADR-0041).
 *
 * RagIndexingScheduler가 이 문을 열지 않은 이유 셋 중 셋째가 «이 저장소에는 아직 스케줄링이 없다 —
 * 한 기능을 위해 여는 것은 대가가 비대칭»이었다. DatabaseKeepAlive(하루 한 번 SELECT 1)가 둘째
 * 손님이라 그 이유는 사라졌다. 첫째(동시 실행 1건)·둘째(기동 복구가 첫 폴링보다 먼저)는 그
 * 스케줄러만의 사정이라 여전히 자기 실행기를 쓴다 — 여기 문을 열었다고 그쪽을 옮기지 않는다.
 *
 * 기본 스케줄러는 스레드 하나다. 여기 붙는 작업이 늘어 서로를 막기 시작하면 그때
 * `spring.task.scheduling.pool.size`를 올린다 — 지금은 하루 한 번 밀리초짜리 쿼리 하나라 한 스레드가 맞다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {}
