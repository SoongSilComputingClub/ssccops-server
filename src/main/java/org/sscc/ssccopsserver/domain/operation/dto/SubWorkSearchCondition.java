package org.sscc.ssccopsserver.domain.operation.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.format.annotation.DateTimeFormat;
import org.sscc.ssccopsserver.domain.operation.entity.ApprovalStatus;
import org.sscc.ssccopsserver.domain.operation.entity.WorkStatus;
import org.sscc.ssccopsserver.global.apipayload.code.error.CommonErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

/*
 * 하위 업무 목록 조회(OPS-008)의 쿼리 파라미터. '하위 업무' 목록 화면의 필터 칩이 이 조합으로
 * 번역된다 — 전체(조건 없음) · 진행 · 승인대기 · 마감임박 · 지연 · 완료.
 *
 * 상태 코드를 enum이 아니라 문자열로 받는다. 바인딩 단계에서 enum 변환이 실패하면 스프링이
 * 이를 '형식 오류'로 묶어 VALIDATION_FAILED(400)로 내보내는데, 기준 코드 위반은
 * INVALID_CODE_VALUE(400)여야 프론트가 '허용값을 다시 확인'과 '형식 오류'를 나눠 안내할 수
 * 있다 (03_오류_코드 · GlobalExceptionHandler가 요청 본문의 enum을 다루는 방식과 같다).
 *
 * 승인 상태만 복수 값을 받는다. 화면의 '승인대기' 칩이 대기(PENDING)와 재승인필요
 * (REAPPROVAL_REQUIRED) 두 상태를 함께 보여줘야 하기 때문이다 — 반려 후 다시 올라온 건도
 * 승인자 입장에서는 처리해야 할 건이다.
 */
public record SubWorkSearchCondition(
        String workStatus,
        List<String> approvalStatus,
        Boolean isOverdue,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime dueBefore,
        @Min(value = 1, message = "size는 1 이상이어야 합니다.")
                @Max(
                        value = SubWorkSearchCondition.MAX_SIZE,
                        message = "size는 " + SubWorkSearchCondition.MAX_SIZE + " 이하여야 합니다.")
                Integer size,
        String cursor,
        String sort) {

    // AP-13 — size 기본 20 · 최대 100
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    /*
     * 문자열을 해석해 조회용 조건으로 바꾼다. 기준 코드 위반·커서 해독 실패는 여기서 걸러
     * Repository까지 내려가지 않게 한다.
     */
    public SubWorkSearchQuery toQuery(Instant overdueBefore) {
        SubWorkSortOrder sortOrder = SubWorkSortOrder.from(sort);
        return new SubWorkSearchQuery(
                toWorkStatus(),
                toApprovalStatuses(),
                Boolean.TRUE.equals(isOverdue),
                dueBefore == null ? null : dueBefore.toInstant(),
                overdueBefore,
                size == null ? DEFAULT_SIZE : size,
                sortOrder,
                SubWorkCursor.decode(cursor, sortOrder));
    }

    private WorkStatus toWorkStatus() {
        return workStatus == null || workStatus.isBlank()
                ? null
                : toEnum(WorkStatus.class, workStatus);
    }

    private Set<ApprovalStatus> toApprovalStatuses() {
        if (approvalStatus == null || approvalStatus.isEmpty()) {
            return EnumSet.noneOf(ApprovalStatus.class);
        }
        return approvalStatus.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> toEnum(ApprovalStatus.class, value))
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(ApprovalStatus.class)));
    }

    private static <E extends Enum<E>> E toEnum(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value.strip());
        } catch (IllegalArgumentException ex) {
            throw new GeneralException(CommonErrorCode.INVALID_CODE_VALUE);
        }
    }
}
