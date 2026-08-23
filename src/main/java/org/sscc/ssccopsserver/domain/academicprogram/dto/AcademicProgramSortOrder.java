package org.sscc.ssccopsserver.domain.academicprogram.dto;

import java.time.Instant;
import java.util.Arrays;

import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.global.apipayload.code.error.CommonErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

/*
 * 목록 조회(#131)의 sort 파라미터. work 도메인의 WorkSortOrder를 그대로 미러링한다(설계 결정
 * #3) — 내림차순은 필드명 앞의 '-'로 표기한다.
 *
 * 두 정렬 키 모두 이 API에서는 NULL일 수 없다 — createdAt은 감사 컬럼이고, eventBgngDt는
 * AcademicProgramCreateRequest가 @NotNull로 요구하므로 이 도메인이 만드는 Event는 beginAt이
 * 항상 채워진다(다른 Event 소비자에는 이 제약이 없지만 이 목록은 academic_program을 경유하므로
 * 영향받지 않는다). WorkSortOrder와 달리 isNullableKey가 늘 false인 이유다.
 */
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public enum AcademicProgramSortOrder {
    CREATED_AT_DESC("-createdAt", SortKey.CREATED_AT, true),
    CREATED_AT_ASC("createdAt", SortKey.CREATED_AT, false),
    EVENT_BGNG_DT_ASC("eventBgngDt", SortKey.EVENT_BGNG_DT, false),
    EVENT_BGNG_DT_DESC("-eventBgngDt", SortKey.EVENT_BGNG_DT, true);

    // 등록 최신순이 기본이다 — 방금 제출한 기획안이 첫 페이지 맨 위에 온다
    public static final AcademicProgramSortOrder DEFAULT = CREATED_AT_DESC;

    private final String parameter;
    private final SortKey key;
    private final boolean descending;

    /*
     * 알 수 없는 표기는 조용히 기본값으로 떨어뜨리지 않는다 — 오타 난 정렬로 목록을 받으면
     * 클라이언트는 서버가 정렬해 준 줄 알고 그대로 그린다 (WorkSortOrder와 같은 판단).
     */
    public static AcademicProgramSortOrder from(String parameter) {
        if (parameter == null || parameter.isBlank()) {
            return DEFAULT;
        }
        return Arrays.stream(values())
                .filter(order -> order.parameter.equals(parameter.strip()))
                .findFirst()
                .orElseThrow(() -> new GeneralException(CommonErrorCode.INVALID_CODE_VALUE));
    }

    public Instant sortValueOf(AcademicProgramEntity academicProgram) {
        return key == SortKey.EVENT_BGNG_DT
                ? academicProgram.getEvent().getBeginAt()
                : academicProgram.getCreatedAt();
    }

    // 두 정렬 키 모두 이 API에서는 NULL일 수 없다 (클래스 주석 참고)
    public boolean isNullableKey() {
        return false;
    }

    public enum SortKey {
        CREATED_AT,
        EVENT_BGNG_DT
    }
}
