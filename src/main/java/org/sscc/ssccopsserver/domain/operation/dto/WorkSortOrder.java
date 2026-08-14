package org.sscc.ssccopsserver.domain.operation.dto;

import java.time.Instant;
import java.util.Arrays;

import org.sscc.ssccopsserver.domain.operation.entity.WorkEntity;
import org.sscc.ssccopsserver.global.apipayload.code.error.CommonErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

/*
 * 상위 업무 목록 조회(OPS-020)의 sort 파라미터. 내림차순은 필드명 앞의 '-'로 표기한다 (AP-13).
 *
 * 하위 업무의 SubWorkSortOrder와 형태가 같지만 정렬 키가 다르다 — 상위 업무에는 마감 컬럼이
 * 없어 AGG-04(마감 오름차순)를 쓸 수 없다. 두 enum을 하나로 합치지 않은 것은 정렬 키를
 * 꺼내는 일이 각자의 엔티티에 묶여 있기 때문이며, 목록 API가 하나 더 늘어 같은 구조가
 * 세 번 반복될 때 커서·정렬의 공통 부분을 따로 뽑는다.
 *
 * JPQL 경로를 여기에 두지 않는다 — 이 enum은 API 계약이고 매핑은 Repository의 몫이다 (LY-03).
 */
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public enum WorkSortOrder {
    CREATED_AT_DESC("-createdAt", SortKey.CREATED_AT, true),
    CREATED_AT_ASC("createdAt", SortKey.CREATED_AT, false),
    START_AT_ASC("startAt", SortKey.START_AT, false),
    START_AT_DESC("-startAt", SortKey.START_AT, true);

    /*
     * 정렬 기본값은 등록 최신순이다 (AGG-06). 시안의 카드 순서에서 규칙을 읽어낼 수 없어
     * 서버가 정해야 했고, 방금 등록한 업무가 첫 페이지 맨 위에 오는 것이 등록 직후 목록으로
     * 돌아오는 흐름과 맞는다.
     */
    public static final WorkSortOrder DEFAULT = CREATED_AT_DESC;

    private final String parameter;
    private final SortKey key;
    private final boolean descending;

    /*
     * 쿼리 파라미터 표기를 enum으로 바꾼다. 값을 생략하면 기본 정렬이다.
     *
     * 알 수 없는 표기는 조용히 기본값으로 떨어뜨리지 않는다 — 오타 난 정렬로 목록을 받으면
     * 클라이언트는 서버가 정렬해 준 줄 알고 그대로 그린다 (SubWorkSortOrder와 같은 판단).
     */
    public static WorkSortOrder from(String parameter) {
        if (parameter == null || parameter.isBlank()) {
            return DEFAULT;
        }
        return Arrays.stream(values())
                .filter(order -> order.parameter.equals(parameter.strip()))
                .findFirst()
                .orElseThrow(() -> new GeneralException(CommonErrorCode.INVALID_CODE_VALUE));
    }

    /*
     * 다음 커서에 실을 정렬 키 값. 두 키 모두 work가 아니라 그 oper의 컬럼이다 —
     * 제목·기간·등록시각 같은 공통 속성은 부모 테이블이 갖는다.
     */
    public Instant sortValueOf(WorkEntity work) {
        return key == SortKey.START_AT
                ? work.getOperation().getBeginAt()
                : work.getOperation().getCreatedAt();
    }

    // 정렬 키가 NULL을 가질 수 있는지. oper.crt_dt는 NOT NULL이라 시작 일시만 해당한다
    public boolean isNullableKey() {
        return key == SortKey.START_AT;
    }

    /*
     * 정렬 대상 컬럼. 방향과 무관한 '무엇으로 정렬하는가'만 담는다 — 커서 비교식이
     * 오름/내림차순에서 갈리기 때문에 방향과 키를 따로 다뤄야 한다.
     */
    public enum SortKey {
        CREATED_AT,
        START_AT
    }
}
