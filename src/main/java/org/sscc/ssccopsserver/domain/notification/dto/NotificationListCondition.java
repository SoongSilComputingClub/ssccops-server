package org.sscc.ssccopsserver.domain.notification.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.sscc.ssccopsserver.domain.notification.code.NotificationApp;

/*
 * 알림 목록 조건 (ssccops#446). 필드 이름이 곧 쿼리 파라미터 이름이다(@ModelAttribute).
 * size 기본 20·상한 100은 AP-13.
 *
 * **app은 선택이고, 없으면 전부다**(#535 · ADR-0047 — «전체» 칩). 기본값을 두지 않은 것은
 * 어느 앱도 «기본»이 아니기 때문이다 — 세 앱이 각자 자기 값을 실어 부르고, 한 회원의 알림을
 * 통째로 보려는 화면만 값을 빼고 부른다. 코드에 없는 값은 바인딩 단계에서 400이다.
 */
public record NotificationListCondition(
        NotificationApp app,
        @Min(value = 1, message = "size는 1 이상이어야 합니다.")
                @Max(value = MAX_SIZE, message = "size는 " + MAX_SIZE + " 이하여야 합니다.")
                Integer size,
        String cursor) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public int sizeOrDefault() {
        return size == null ? DEFAULT_SIZE : size;
    }
}
