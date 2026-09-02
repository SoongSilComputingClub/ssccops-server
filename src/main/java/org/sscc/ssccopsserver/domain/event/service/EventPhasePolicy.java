package org.sscc.ssccopsserver.domain.event.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.event.code.EventPhase;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;

import lombok.RequiredArgsConstructor;

/*
 * "이 행사가 지금 어느 단계인가"의 유일한 구현 (ssccops#139 · D9).
 *
 * 목록·상세·전이 응답이 전부 같은 질문을 한다 — 여러 곳에서 각자 판정하면 경계 포함 여부
 * 같은 차이가 반드시 생긴다 (FormReceiptPolicy와 같은 배치). 공개 행사 조회(ssccops#143)도
 * 이 판정을 다시 구현하지 말고 여기를 호출한다.
 *
 *   NONE     = 시작·종료 일시가 둘 다 NULL (일시 미정 공지)
 *   UPCOMING = 시작 일시가 있고 now < 시작
 *   ENDED    = 종료 일시가 있고 now > 종료
 *   ONGOING  = 그 외 (시작 정각·종료 정각은 진행 중이다 — 경계는 포함)
 *
 * 저장하지 않는다 — 컬럼으로 두면 "종료된 행사"를 만들 배치가 필요해지고, 그 배치가 폼 자동
 * 마감을 두지 않은 이유(#33)와 같은 문제를 다시 만든다. now는 주입된 Clock에서 온다
 * (global/config/ClockConfig) — 직접 부르면 경계 판정을 테스트에서 고정할 수 없다.
 */
@Component
@RequiredArgsConstructor
public class EventPhasePolicy {

    private final Clock clock;

    public EventPhase phaseOf(EventEntity event) {
        Instant beginAt = event.getBeginAt();
        Instant endAt = event.getEndAt();
        if (beginAt == null && endAt == null) {
            return EventPhase.NONE;
        }

        Instant now = clock.instant();
        if (beginAt != null && now.isBefore(beginAt)) {
            return EventPhase.UPCOMING;
        }
        if (endAt != null && now.isAfter(endAt)) {
            return EventPhase.ENDED;
        }
        return EventPhase.ONGOING;
    }
}
