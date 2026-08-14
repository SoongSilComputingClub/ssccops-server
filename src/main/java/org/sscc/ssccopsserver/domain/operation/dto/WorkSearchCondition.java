package org.sscc.ssccopsserver.domain.operation.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.sscc.ssccopsserver.domain.operation.entity.WorkStatus;
import org.sscc.ssccopsserver.domain.operation.entity.WorkType;
import org.sscc.ssccopsserver.global.apipayload.code.error.CommonErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

/*
 * 상위 업무 목록 조회(OPS-020)의 쿼리 파라미터. '운영 통합 › 업무' 화면의 카드 그리드가
 * 이 조건으로 채워진다.
 *
 * 시안에는 필터 UI가 없지만 상태·유형 두 가지는 카드 배지에 그려지는 값이라 필터로 열어 둔다.
 * 담당자·기간·제목 검색은 화면에도 정의서에도 근거가 없어 넣지 않는다 — 필요해지면 여기에
 * 필드를 더하면 되고, 컨트롤러 시그니처는 그대로다.
 *
 * 상태 코드를 enum이 아니라 문자열로 받는 이유는 SubWorkSearchCondition과 같다. 바인딩
 * 단계에서 enum 변환이 실패하면 스프링이 '형식 오류'로 묶어 VALIDATION_FAILED(400)를 내는데,
 * 기준 코드 위반은 INVALID_CODE_VALUE(400)여야 프론트가 둘을 나눠 안내할 수 있다.
 */
public record WorkSearchCondition(
        String workStatus,
        String workType,
        @Min(value = 1, message = "size는 1 이상이어야 합니다.")
                @Max(
                        value = WorkSearchCondition.MAX_SIZE,
                        message = "size는 " + WorkSearchCondition.MAX_SIZE + " 이하여야 합니다.")
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
    public WorkSearchQuery toQuery() {
        WorkSortOrder sortOrder = WorkSortOrder.from(sort);
        return new WorkSearchQuery(
                toEnum(WorkStatus.class, workStatus),
                toEnum(WorkType.class, workType),
                size == null ? DEFAULT_SIZE : size,
                sortOrder,
                WorkCursor.decode(cursor, sortOrder));
    }

    private static <E extends Enum<E>> E toEnum(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.strip());
        } catch (IllegalArgumentException ex) {
            throw new GeneralException(CommonErrorCode.INVALID_CODE_VALUE);
        }
    }
}
