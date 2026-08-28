package org.sscc.ssccopsserver.domain.academicprogram.service;

import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionCrossCondition;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionCrossSearchResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionReviewCondition;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionTransitionRequest;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionTransitionResponse;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/*
 * 학술국장의 회차 검토(#136 · 학술관리_API설계.md §3.6). 승인·수정요청 전이와, 활동 경계를
 * 넘어 회차를 훑는 두 목록이 여기 모인다.
 *
 * 기록 작성·조회(SessionService, #135)와 서비스를 나눈 것은 판정의 근거가 다르기 때문이다 —
 * 그쪽은 "이 활동의 leadrMbrId 본인인가"라는 레코드 단위 소유권이고, 이쪽은
 * ACADEMIC_PROGRAM_MANAGE라는 전역 권한이다. 한 빈에 두면 활동에 매인 규칙과 활동을
 * 가로지르는 규칙이 섞여, 어느 메서드가 어느 판정을 지고 있는지 호출부에서 읽히지 않는다.
 */
public interface SessionReviewService {

    /*
     * 승인·수정요청(POST .../sessions/{sessionId}/transitions). SUBMITTED에서만 성립하며
     * (APPROVED는 되돌리지 않는다) 처리 결과를 acdm_actv_aprv에 한 건 남긴다.
     */
    SessionTransitionResponse transitionSession(
            Long academicProgramId,
            Long sessionId,
            SessionTransitionRequest request,
            MemberEntity approver);

    /* 회차 이력(GET /v1/academic-programs/sessions). 활동 경계 없이 필터링·검색한다 */
    SessionCrossSearchResponse searchCrossSessions(SessionCrossCondition condition);

    /* 승인 대기 목록(GET /v1/academic-programs/reviews/sessions). 위 목록에 SUBMITTED가 고정된 특수형이다 */
    SessionCrossSearchResponse searchPendingSessions(SessionReviewCondition condition);
}
