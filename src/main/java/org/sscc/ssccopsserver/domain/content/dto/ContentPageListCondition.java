package org.sscc.ssccopsserver.domain.content.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.sscc.ssccopsserver.domain.content.code.ContentPublishStatus;

/*
 * 어드민 페이지 목록 조건 (ssccops#381). pubSttsCd는 선택 필터.
 * 필드 이름이 곧 쿼리 파라미터 이름이다(@ModelAttribute · McpRestClient.getList도 같은 규칙).
 * size 기본 20·상한 100은 AP-13.
 */
public record ContentPageListCondition(
        ContentPublishStatus pubSttsCd,
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
