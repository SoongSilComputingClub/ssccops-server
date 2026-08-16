package org.sscc.ssccopsserver.domain.operation.dto;

import java.time.Instant;
import java.util.Arrays;

import org.sscc.ssccopsserver.domain.operation.entity.SubWorkEntity;
import org.sscc.ssccopsserver.global.apipayload.code.error.CommonErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

/*
 * 하위 업무 목록 조회(OPS-008)의 sort 파라미터. 내림차순은 필드명 앞의 '-'로 표기한다 (AP-13).
 *
 * 정렬 키를 자유 문자열로 받지 않고 enum으로 못 박는 이유는 두 가지다. 정렬 키가 곧 커서의
 * 비교 기준이라 아무 컬럼이나 허용하면 커서를 만들 수 없고, 정렬 컬럼에는 인덱스가 필요해
 * (DB-17) 늘리는 것이 공짜가 아니기 때문이다.
 *
 * JPQL 경로를 여기에 두지 않는다 — 이 enum은 API 계약이고 매핑은 Repository의 몫이다 (LY-03).
 * 대신 커서에 실을 정렬 키 값을 꺼내는 일은 여기서 한다. 어떤 값으로 정렬했는지 아는 쪽이
 * 그 값을 꺼내야 둘이 어긋나지 않는다.
 */
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public enum SubWorkSortOrder {
    DUE_AT_ASC("dueAt", SortKey.DUE_AT, false),
    DUE_AT_DESC("-dueAt", SortKey.DUE_AT, true),
    CREATED_AT_ASC("createdAt", SortKey.CREATED_AT, false),
    CREATED_AT_DESC("-createdAt", SortKey.CREATED_AT, true);

    /*
     * 정렬 기본값은 마감 오름차순이다 (AGG-04). 마감이 빠른 건과 이미 지난 건이 위로 오므로
     * '지금 손봐야 하는 건'이 첫 페이지에 모인다.
     */
    public static final SubWorkSortOrder DEFAULT = DUE_AT_ASC;

    private final String parameter;
    private final SortKey key;
    private final boolean descending;

    /*
     * 쿼리 파라미터 표기를 enum으로 바꾼다. 값을 생략하면 기본 정렬이다.
     *
     * 알 수 없는 표기는 조용히 기본값으로 떨어뜨리지 않는다 — 오타 난 정렬로 목록을 받으면
     * 클라이언트는 서버가 정렬해 준 줄 알고 그대로 그린다. 기준 코드 위반과 같은 성격이라
     * INVALID_CODE_VALUE(400)로 돌려준다.
     */
    public static SubWorkSortOrder from(String parameter) {
        if (parameter == null || parameter.isBlank()) {
            return DEFAULT;
        }
        return Arrays.stream(values())
                .filter(order -> order.parameter.equals(parameter.strip()))
                .findFirst()
                .orElseThrow(() -> new GeneralException(CommonErrorCode.INVALID_CODE_VALUE));
    }

    // 다음 커서에 실을 정렬 키 값. 마감 정렬에서는 NULL일 수 있다(마감 없는 하위 업무)
    public Instant sortValueOf(SubWorkEntity subWork) {
        return key == SortKey.DUE_AT ? subWork.getDueAt() : subWork.getOperation().getCreatedAt();
    }

    // 정렬 키가 NULL을 가질 수 있는지. oper.crt_dt는 NOT NULL이라 마감만 해당한다
    public boolean isNullableKey() {
        return key == SortKey.DUE_AT;
    }

    /*
     * 정렬 대상 컬럼. 방향과 무관한 '무엇으로 정렬하는가'만 담는다 — 커서 비교식이
     * 오름/내림차순에서 갈리기 때문에 방향과 키를 따로 다뤄야 한다.
     */
    public enum SortKey {
        DUE_AT,
        CREATED_AT
    }
}
