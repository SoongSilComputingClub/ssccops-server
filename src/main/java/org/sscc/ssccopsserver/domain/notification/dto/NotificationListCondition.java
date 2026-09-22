package org.sscc.ssccopsserver.domain.notification.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/*
 * 알림 목록 조건 (ssccops#446). 필드 이름이 곧 쿼리 파라미터 이름이다(@ModelAttribute).
 * size 기본 20·상한 100은 AP-13.
 */
public record NotificationListCondition(
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
