package org.sscc.ssccopsserver.domain.academicprogram.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.sscc.ssccopsserver.domain.academicprogram.entity.SessionStatus;
import org.sscc.ssccopsserver.global.apipayload.code.error.CommonErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

/*
 * 활동 횡단 회차 이력(#136 · GET /v1/academic-programs/sessions)의 쿼리 파라미터.
 *
 * 활동 하나짜리 목록의 SessionCondition과 record를 나눈 것은 받는 값이 다르기 때문이다 —
 * 이쪽에만 keyword·academicProgramId가 있고, 저쪽의 활동은 경로 변수라 쿼리에 자리가 없다.
 * 한 record로 합치면 활동 상세의 목록이 keyword를 받아 놓고 조용히 버리는 자리가 생긴다.
 * 대신 해석 결과(SessionSearchQuery)와 커서·정렬 어휘는 공유하므로 규칙이 두 벌이 되지는
 * 않는다.
 *
 * 기본 정렬이 진행일 내림차순인 것은 이 화면이 "최근에 열린 회차부터" 훑는 이력이기 때문이다.
 * 회차 번호 오름차순(활동 상세의 기본값)은 활동을 가로지르면 뜻을 잃는다 — 여러 활동의
 * 1회차가 앞에 뭉친다.
 *
 * academicProgramId는 경로가 아니라 쿼리로 받는 선택 필터다. 화면이 "이 활동만" 좁혀 보는
 * 것과 활동 상세의 목록은 다른 API이며(설계 결정 #1), 여기서 좁혔다고 활동 상세용 응답
 * (SessionSummaryResponse)으로 바뀌지는 않는다.
 */
public record SessionCrossCondition(
        String sttsCd,
        Long academicProgramId,
        String keyword,
        @Min(value = 1, message = "size는 1 이상이어야 합니다.")
                @Max(
                        value = SessionCondition.MAX_SIZE,
                        message = "size는 " + SessionCondition.MAX_SIZE + " 이하여야 합니다.")
                Integer size,
        String cursor,
        String sort) {

    public static final SessionSortOrder DEFAULT_SORT = SessionSortOrder.REAL_DT_DESC;

    public SessionSearchQuery toQuery() {
        SessionSortOrder sortOrder = SessionSortOrder.from(sort, DEFAULT_SORT);
        return new SessionSearchQuery(
                academicProgramId,
                toStatus(),
                blankToNull(keyword),
                size == null ? SessionCondition.DEFAULT_SIZE : size,
                sortOrder,
                SessionCursor.decode(cursor, sortOrder));
    }

    /*
     * NOT_SUBMITTED를 넘기면 언제나 빈 목록이다 — 그 상태는 sesn 행이 없다는 사실을 가리키는
     * 파생 값이라 이 테이블에서 셀 수 있는 것이 아니다(SessionCondition과 같은 판단).
     */
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
