package org.sscc.ssccopsserver.domain.academicprogram.dto;

import org.sscc.ssccopsserver.domain.academicprogram.entity.SessionStatus;

/*
 * 회차 승인·수정요청 응답 (#136). 전이 전/후 상태를 함께 내리는 것은 활동 전이
 * (AcademicProgramTransitionResponse)·하위 업무 전이와 같은 규약이다(학술관리_API설계.md §2) —
 * 화면이 방금 무엇이 바뀌었는지를 재조회 없이 안다.
 *
 * 승인 이력(acdm_actv_aprv)의 식별자는 싣지 않는다. 화면이 그 행을 직접 가리켜 할 일이
 * 없고(사유는 회차 상세의 latestOpinion으로 읽는다), 실으면 이력 행을 자원처럼 다루는 경로가
 * 생긴다 — 그 테이블을 조회하는 길은 승인 이력 API 하나로 남긴다.
 */
public record SessionTransitionResponse(
        Long sessionId, SessionStatus beforeSttsCd, SessionStatus afterSttsCd) {

    public static SessionTransitionResponse of(
            Long sessionId, SessionStatus beforeSttsCd, SessionStatus afterSttsCd) {
        return new SessionTransitionResponse(sessionId, beforeSttsCd, afterSttsCd);
    }
}
