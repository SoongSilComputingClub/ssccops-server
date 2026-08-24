package org.sscc.ssccopsserver.domain.academicprogram.service;

import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionCondition;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionDetailResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionSearchResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionSubmitRequest;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/*
 * 회차 실적(session) 기록·조회 (#135 · 학술관리_API설계.md §3.4).
 *
 * 최초 제출(POST)과 재제출(PUT)을 한 메서드로 합치지 않는다 — 성립 조건이 서로 배타적이고
 * (실적이 없어야 한다 / 실적이 REVISION_REQUESTED여야 한다) 실패 코드도 다르다. 하나로 묶으면
 * "요청에 sessionId가 있으면 재제출"처럼 본문 모양으로 갈리는 분기가 생기고, 그 분기가 곧
 * 상태 검사를 건너뛰는 자리가 된다(폼 도메인이 제출과 자동 저장을 두 경로로 나눈 것과 같은 판단).
 *
 * 승인·수정요청은 이 서비스에 없다 — 학술국장의 회차 승인(#136)이 별도 전이 경로로 맡는다.
 */
public interface SessionService {

    /*
     * 신규 제출(POST .../sessions). 소유권(leadrMbrId 본인) 판정을 통과해야 하며, 대상
     * 커리큘럼 항목에 실적이 이미 있으면 409다.
     */
    SessionDetailResponse submitSession(
            Long academicProgramId, SessionSubmitRequest request, MemberEntity requester);

    /*
     * 재제출(PUT .../sessions/{sessionId}). REVISION_REQUESTED 전용이며 전체 교체다 — 이전
     * 내용은 이력을 남기지 않고 덮어쓴다(데이터모델 §7).
     */
    SessionDetailResponse resubmitSession(
            Long academicProgramId,
            Long sessionId,
            SessionSubmitRequest request,
            MemberEntity requester);

    /* 회차 상세. 인증만 요구한다 — 팀원도 자기 활동의 회차를 본다 */
    SessionDetailResponse getSession(Long academicProgramId, Long sessionId);

    /* 회차 목록. 활동 상세 화면 안에서 그 활동의 회차만 보는 용도다 */
    SessionSearchResponse searchSessions(Long academicProgramId, SessionCondition condition);
}
