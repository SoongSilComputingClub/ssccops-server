package org.sscc.ssccopsserver.domain.member.dto;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.sscc.ssccopsserver.domain.member.code.MemberGradeCode;
import org.sscc.ssccopsserver.domain.member.code.MemberStatusCode;
import org.sscc.ssccopsserver.global.apipayload.code.error.CommonErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

/*
 * 회원 목록 조회(GET /v1/members)의 쿼리 파라미터 (#76). 회원 관리 화면의 표가 이 조건으로
 * 채워진다.
 *
 * 조건을 개별 @RequestParam으로 늘어놓지 않고 record 하나로 받는 것은 필터가 늘 때마다
 * 컨트롤러 시그니처가 자라는 것을 막기 위해서다 (WorkSearchCondition과 같은 판단).
 *
 * 이름을 데이터사전의 컬럼명(mbrGrdCd·mbrSttsCd)으로 둔 것은 이슈 #76의 API 계약 그대로이며,
 * 정렬 표기(mbrNm·genNo·joinYmd·mdfcnDt)와 어휘를 맞추기 위해서다.
 *
 * 등급·상태를 enum이 아니라 문자열 목록으로 받는 이유는 WorkSearchCondition과 같다. 바인딩
 * 단계에서 enum 변환이 실패하면 스프링이 '형식 오류'로 묶어 VALIDATION_FAILED(400)를 내는데,
 * 기준 코드 위반은 INVALID_CODE_VALUE(400)여야 프론트가 둘을 나눠 안내할 수 있다.
 */
public record MemberSearchCondition(
        String q,
        List<String> mbrGrdCd,
        List<String> mbrSttsCd,
        @Min(value = 1, message = "size는 1 이상이어야 합니다.")
                @Max(
                        value = MemberSearchCondition.MAX_SIZE,
                        message = "size는 " + MemberSearchCondition.MAX_SIZE + " 이하여야 합니다.")
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
    public MemberSearchQuery toQuery() {
        MemberSortOrder sortOrder = MemberSortOrder.from(sort);
        return new MemberSearchQuery(
                trimToNull(q),
                validatedCodes(MemberGradeCode.class, mbrGrdCd),
                validatedCodes(MemberStatusCode.class, mbrSttsCd),
                size == null ? DEFAULT_SIZE : size,
                sortOrder,
                MemberCursor.decode(cursor, sortOrder));
    }

    /*
     * 기준 코드 안의 값만 통과시킨다. 검증하지 않고 그대로 IN에 넣으면 오타 난 필터가 조용히
     * 빈 목록을 만들어, 화면은 '해당 회원이 없다'로 읽는다.
     *
     * 중복은 걷어내되 넘어온 순서는 지킨다(LinkedHashSet) — IN 목록의 순서가 결과를 바꾸지는
     * 않지만, 같은 요청이 늘 같은 쿼리 문자열을 만들어야 실행 계획 캐시가 흩어지지 않는다.
     */
    private static <E extends Enum<E>> List<String> validatedCodes(
            Class<E> type, List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> codes = new LinkedHashSet<>();
        for (String value : values) {
            String code = trimToNull(value);
            // 빈 값은 필터를 걸지 않겠다는 뜻으로 본다 — 화면의 '전체' 선택이 빈 문자열로 온다
            if (code == null) {
                continue;
            }
            try {
                codes.add(Enum.valueOf(type, code).name());
            } catch (IllegalArgumentException ex) {
                throw new GeneralException(CommonErrorCode.INVALID_CODE_VALUE);
            }
        }
        return List.copyOf(codes);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
