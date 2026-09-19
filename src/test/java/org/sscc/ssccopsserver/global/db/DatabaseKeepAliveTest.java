package org.sscc.ssccopsserver.global.db;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/*
 * 컨텍스트 없이 — 이 빈의 계약은 둘뿐이다: SELECT 1을 보낸다, 실패해도 던지지 않는다.
 * 스케줄 자체(cron·zone)는 애노테이션이고 여기서 시계를 돌리지 않는다.
 */
class DatabaseKeepAliveTest {

    @Test
    @DisplayName("SELECT 1을 보낸다")
    void sendsSelectOne() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);

        new DatabaseKeepAlive(jdbc).ping("test");

        verify(jdbc).queryForObject(eq("SELECT 1"), eq(Integer.class));
    }

    @Test
    @DisplayName("DB에 닿지 않아도 던지지 않는다 — 로그로만 남긴다")
    void swallowsFailure() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT 1", Integer.class))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        assertThatCode(() -> new DatabaseKeepAlive(jdbc).ping("test")).doesNotThrowAnyException();
    }
}
