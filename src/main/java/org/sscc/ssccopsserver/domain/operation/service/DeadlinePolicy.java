package org.sscc.ssccopsserver.domain.operation.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/*
 * "마감이 지났는가"를 재는 경계 시각의 유일한 구현 (#121).
 *
 * 지연 판정은 시각이 아니라 **일자**로 한다 — 마감일 당일은 지연이 아니고 다음 날 0시부터
 * 지연이다. 초 단위로 재던 동안에는 마감일이 오늘인 하위 업무가 같은 화면 안에서 '지연'
 * (서버 판정)과 'D-DAY'(화면의 날짜 단위 D-day)로 동시에 표시됐다. 화면의 어휘는 마감 표기·
 * D-day·마감임박 임계값까지 전부 날짜 단위이고 마감 시각을 보여주는 자리도 상세뿐이라,
 * 서버만 초 단위로 재면 운영자가 화면만 보고는 납득할 수 없는 상태가 된다.
 *
 * 경계를 만드는 코드가 여기 하나뿐이어야 하는 이유는 판정이 두 곳에서 돌기 때문이다 —
 * 응답값은 SubWorkEntity.isDelayedBefore가, 목록 필터는 SubWorkRepositoryImpl이 같은 조건을
 * SQL로 옮겨 쓴다. 양쪽이 각자 오늘 0시를 계산하면 지금 고치는 어긋남이 형태만 바꿔 되살아난다.
 *
 * 시간대 상수를 여기에 새로 박지 않는다. 주입된 Clock이 이미 서비스 표준 시간대(Asia/Seoul)로
 * 만들어져 있어(global/config/ClockConfig) LocalDate.now(clock)가 그 시간대의 오늘이다 —
 * 응답 DTO들이 직렬화용으로 들고 있는 SERVICE_ZONE을 판정에 다시 복제하면 사본이 하나 더 는다.
 * Instant.now()를 직접 부르지 않는 것은 자정 경계를 테스트에서 고정하기 위해서다.
 */
@Component
@RequiredArgsConstructor
public class DeadlinePolicy {

    private final Clock clock;

    /*
     * 지연·마감임박 판정의 경계 시각 — 서비스 표준 시간대의 오늘 0시.
     *
     * 마감이 이 시각보다 앞이면 지연이고, 이 시각 이후면(오늘 마감을 포함해) 아직 지연이 아니다.
     * 지연 칩과 마감임박 칩이 같은 값을 경계로 쓰므로 두 칩은 서로 겹치지도, 사이에 건을
     * 빠뜨리지도 않는다 — 한쪽만 옮기면 '오늘 09시 마감'인 건이 정오에 어느 칩에도 잡히지 않는다.
     */
    public Instant overdueBefore() {
        return LocalDate.now(clock).atStartOfDay(clock.getZone()).toInstant();
    }
}
