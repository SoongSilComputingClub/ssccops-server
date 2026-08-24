package org.sscc.ssccopsserver.domain.academicprogram.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.sscc.ssccopsserver.domain.academicprogram.entity.SessionStatus;

/*
 * 회차·출석 승인 대기 목록(#136 · GET /v1/academic-programs/reviews/sessions)의 쿼리 파라미터.
 *
 * 이 목록은 회차 이력(GET .../sessions)에 sttsCd = SUBMITTED가 **고정된** 특수형이다(설계 결정
 * #2). 그래서 상태 필터를 받는 자리를 아예 두지 않는다 — SessionCrossCondition을 그대로 쓰고
 * 서버가 상태를 덮어쓰면, sttsCd=APPROVED를 보낸 클라이언트가 SUBMITTED 목록을 받아 놓고
 * 필터가 걸렸다고 믿는다. 받지 않는 파라미터는 바인딩되지 않으므로 계약에 없는 값이 조용히
 * 동작하는 일도 없다.
 *
 * 기본 정렬은 진행일 오름차순 — 오래 기다린 건부터 처리한다. 계획일이 아닌 이유는
 * SessionSortOrder 주석에 있다(그 컬럼은 nullable이라 커서 비교가 성립하지 않는다).
 * size·cursor 규칙은 다른 목록과 같은 값을 쓴다(SessionCondition의 상수를 그대로 참조한다 —
 * 목록마다 상한이 다르면 클라이언트가 API마다 다른 한도를 외워야 한다).
 */
public record SessionReviewCondition(
        @Min(value = 1, message = "size는 1 이상이어야 합니다.")
                @Max(
                        value = SessionCondition.MAX_SIZE,
                        message = "size는 " + SessionCondition.MAX_SIZE + " 이하여야 합니다.")
                Integer size,
        String cursor,
        String sort) {

    public static final SessionSortOrder DEFAULT_SORT = SessionSortOrder.REAL_DT_ASC;

    /** 이 목록의 상태는 요청이 정하지 않는다 — 검토를 기다리는 회차가 곧 SUBMITTED다 */
    public SessionSearchQuery toQuery() {
        SessionSortOrder sortOrder = SessionSortOrder.from(sort, DEFAULT_SORT);
        return new SessionSearchQuery(
                null,
                SessionStatus.SUBMITTED,
                null,
                size == null ? SessionCondition.DEFAULT_SIZE : size,
                sortOrder,
                SessionCursor.decode(cursor, sortOrder));
    }
}
