package org.sscc.ssccopsserver.global.apipayload;

import org.sscc.ssccopsserver.global.apipayload.code.error.ErrorCode;
import org.sscc.ssccopsserver.global.apipayload.code.success.CommonSuccessCode;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@JsonPropertyOrder({"success", "code", "message", "data", "page"})
public class ApiResponse<T> {

    private final boolean success;
    private final String code;
    private final String message;
    private final T data;

    /*
     * 목록 응답에만 실리는 페이지 봉투 (AP-11). 단건·오류 응답에서는 null이며 직렬화에서
     * 빠진다 — 값이 없는 필드를 null로 내리라는 AP-15는 리소스의 속성을 두고 하는 말이고,
     * page는 속성이 아니라 목록 응답에만 존재하는 봉투 구조라 단건 응답의 계약을 늘리지 않는다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final PageResponse page;

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(
                true,
                CommonSuccessCode.SUCCESS.getCode(),
                CommonSuccessCode.SUCCESS.getMessage(),
                data,
                null);
    }

    // 목록 조회용. 커서·건수는 서비스가 계산해 PageResponse로 넘긴다
    public static <T> ApiResponse<T> success(T data, PageResponse page) {
        return new ApiResponse<>(
                true,
                CommonSuccessCode.SUCCESS.getCode(),
                CommonSuccessCode.SUCCESS.getMessage(),
                data,
                page);
    }

    public static ApiResponse<Void> successWithNoData() {
        return new ApiResponse<>(
                true,
                CommonSuccessCode.SUCCESS.getCode(),
                CommonSuccessCode.SUCCESS.getMessage(),
                null,
                null);
    }

    public static <T> ApiResponse<T> created(T data) {
        return new ApiResponse<>(
                true,
                CommonSuccessCode.CREATED.getCode(),
                CommonSuccessCode.CREATED.getMessage(),
                data,
                null);
    }

    public static ApiResponse<Void> createdWithNoData() {
        return new ApiResponse<>(
                true,
                CommonSuccessCode.CREATED.getCode(),
                CommonSuccessCode.CREATED.getMessage(),
                null,
                null);
    }

    public static ApiResponse<?> fail(ErrorCode errorCode) {
        return new ApiResponse<>(false, errorCode.getCode(), errorCode.getMessage(), null, null);
    }

    public static ApiResponse<?> fail(ErrorCode errorCode, String message) {
        return new ApiResponse<>(false, errorCode.getCode(), message, null, null);
    }
}
