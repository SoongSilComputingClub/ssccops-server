package org.sscc.ssccopsserver.domain.academicprogram.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.sscc.ssccopsserver.domain.academicprogram.entity.SessionStatus;
import org.sscc.ssccopsserver.global.apipayload.code.error.CommonErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

/*
 * 회차 목록(#135 · GET .../sessions)의 쿼리 파라미터. AcademicProgramCondition을 미러링한다.
 *
 * sttsCd로 NOT_SUBMITTED를 넘기면 언제나 빈 목록이다 — 그 상태는 session 행이 없다는 사실을
 * 가리키는 파생 값이라 이 테이블에서 셀 수 있는 것이 아니다(데이터모델 §3). 미제출 회차를 보는
 * 화면은 이 목록이 아니라 계획 조회(#134 · GET .../curriculum-items)다. 그렇다고 400으로
 * 거절하지는 않는다 — 어휘에 있는 값이고, "그 조건에 맞는 실적이 없다"는 답이 거짓이 아니다.
 */
public record SessionCondition(
        String sttsCd,
        @Min(value = 1, message = "size는 1 이상이어야 합니다.")
                @Max(
                        value = SessionCondition.MAX_SIZE,
                        message = "size는 " + SessionCondition.MAX_SIZE + " 이하여야 합니다.")
                Integer size,
        String cursor,
        String sort) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public SessionSearchQuery toQuery(Long academicProgramId) {
        SessionSortOrder sortOrder = SessionSortOrder.from(sort);
        return new SessionSearchQuery(
                academicProgramId,
                toStatus(),
                // 활동 하나짜리 목록에는 검색어가 없다 — 활동을 가로지르는 목록(#136)만 쓴다
                null,
                size == null ? DEFAULT_SIZE : size,
                sortOrder,
                SessionCursor.decode(cursor, sortOrder));
    }

    private SessionStatus toStatus() {
        if (sttsCd == null || sttsCd.isBlank()) {
            return null;
        }
        try {
            return SessionStatus.valueOf(sttsCd.strip());
        } catch (IllegalArgumentException ex) {
            throw new GeneralException(CommonErrorCode.INVALID_CODE_VALUE);
        }
    }
}
