package org.sscc.ssccopsserver.domain.academicprogram.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramStatus;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.code.error.CommonErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

/*
 * 목록 조회(#131 · GET /v1/academic-programs)의 쿼리 파라미터. work 도메인의
 * WorkSearchCondition을 그대로 미러링한다(설계 결정 #3).
 *
 * sttsCd는 이 코드베이스의 고정 enum(AcademicProgramStatus)이라 Work의 workStatus와 같이
 * Enum.valueOf 실패를 INVALID_CODE_VALUE로 옮긴다. typeCd는 반대로 academic_program_type이라는
 * 런타임 코드테이블의 PK라 여기서는 검증하지 않는다 — 존재 여부는 DB 조회가 필요한 참조
 * 무결성 문제이지 형식 문제가 아니다(생성 시의 typeCd 검증과 다른 층위). 존재하지 않는
 * typeCd로 필터링하면 그냥 빈 목록이 된다.
 */
public record AcademicProgramCondition(
        String typeCd,
        String sttsCd,
        String keyword,
        Boolean mine,
        @Min(value = 1, message = "size는 1 이상이어야 합니다.")
                @Max(
                        value = AcademicProgramCondition.MAX_SIZE,
                        message = "size는 " + AcademicProgramCondition.MAX_SIZE + " 이하여야 합니다.")
                Integer size,
        String cursor,
        String sort) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public AcademicProgramSearchQuery toQuery(MemberEntity viewer) {
        AcademicProgramSortOrder sortOrder = AcademicProgramSortOrder.from(sort);
        return new AcademicProgramSearchQuery(
                toStatus(),
                blankToNull(typeCd),
                blankToNull(keyword),
                Boolean.TRUE.equals(mine) ? viewer : null,
                size == null ? DEFAULT_SIZE : size,
                sortOrder,
                AcademicProgramCursor.decode(cursor, sortOrder));
    }

    private AcademicProgramStatus toStatus() {
        if (sttsCd == null || sttsCd.isBlank()) {
            return null;
        }
        try {
            return AcademicProgramStatus.valueOf(sttsCd.strip());
        } catch (IllegalArgumentException ex) {
            throw new GeneralException(CommonErrorCode.INVALID_CODE_VALUE);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
