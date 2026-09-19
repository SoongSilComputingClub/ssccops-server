package org.sscc.ssccopsserver.global.db;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/*
 * DB에 하루 한 번 «사용자 쿼리»를 남긴다 — Supabase Free 일시정지 방지 1차 (#487 · ssccops#394 · ADR-0041).
 *
 * dev·prod의 앱 DB는 Supabase Postgres이고 Free 플랜은 7일간 사용자 DB 쿼리가 적으면 프로젝트를
 * 멈춘다. 멈추면 헬스체크(DB 포함 · ADR-0022)가 unhealthy → autoheal 재시작 루프 → API 전체 장애다.
 * 학기 중엔 실제 요청이 있어 괜찮지만 방학엔 dev는 확실히, prod도 자주 문턱 아래로 떨어진다.
 *
 * HikariCP 풀·헬스체크의 `isValid()`가 그 «사용자 쿼리»로 셀지는 Supabase 문서가 말하지 않으므로
 * 명시적인 SQL 한 줄을 보낸다. `SELECT 1`이지 V17의 `heartbeat()`가 아니다 — 그 함수는 Actions(2차)의
 * 것이고, 이 쪽은 함수가 없는 환경(로컬·test H2)에서도 같아야 한다.
 *
 * 실패는 `ERROR` 로그로만 남긴다(ELK) — 던지면 스케줄러 스레드가 멈추는 것은 아니지만 얻는 것도 없다.
 * 이 로그를 사람이 보지 않는다는 것이 2차(Actions · 빨개지는 job)가 있는 이유다.
 *
 * `enabled=false`면 빈이 서지 않는다 — test 프로필이 그렇다(공용 컨텍스트에 기동 쿼리를 얹지 않는다).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ssccops.db.keepalive.enabled", havingValue = "true", matchIfMissing = true)
public class DatabaseKeepAlive {

    private final JdbcTemplate jdbc;

    public DatabaseKeepAlive(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 기동 직후 한 번 — 배포가 잦은 dev에서는 사실상 이것이 매일의 쿼리다 */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        ping("startup");
    }

    /** 매일 04:00 KST — 트래픽이 가장 없는 시각. 서버가 며칠 켜져만 있어도 하루 한 건은 남는다 */
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    public void daily() {
        ping("daily");
    }

    void ping(String reason) {
        try {
            Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
            log.info("db keepalive ok ({}) → {}", reason, one);
        } catch (RuntimeException e) {
            log.error("db keepalive failed ({}) — Supabase가 멈췄거나 DB에 닿지 않는다: {}", reason, e.getMessage());
        }
    }
}
