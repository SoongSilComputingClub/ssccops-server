package org.sscc.ssccopsserver.domain.academicprogram.service;

import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionCondition;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionDetailResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionSearchResponse;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionSubmitRequest;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/*
 * 회차 실적(sesn) 기록·조회 (#135 · 학술관리_API설계.md §3.4).
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

    /*
     * 회차 상세. 인증만 요구한다 — 팀원도 자기 활동의 회차를 본다.
     *
     * **주체를 받는 것은 출석 인증사진 때문이다** (#200). 사진은 그 활동의 관계자에게만 서명된
     * URL로 내려주므로(팀원·스터디장·학술국장) 상세 조립에 요청자가 필요하다.
     *
     * ⚠️ 2026-10-18 정정(#688 · ssccops#611): 여기는 «조회 자체는 인증만이며 좁히는 것은 사진
     * 하나다»라고 적혀 있었다. **이제 조회 자체가 관계자만이다**(AcademicProgramOwnershipPolicy
     * .requireRelated · 아니면 403) — «팀원도 봐야 한다»가 근거였는데 코드는 동아리 전원에게 열어
     * 남의 프로그램 출석부·진행 내용이 읽혔다. 수정요청 사유(latestOpinion)는 리더·학술국장만이다.
     */
    SessionDetailResponse getSession(
            Long academicProgramId, Long sessionId, MemberEntity requester);

    /*
     * 회차 id 하나로 읽는 상세 (#316). 위의 getSession과 **결과가 같고 좁히는 값만 다르다** —
     * 활동 id를 함께 받지 않으므로 남의 활동 회차도 그대로 읽힌다.
     *
     * **그것이 이 메서드의 목적이다.** 공유 링크가 들고 오는 것은 대상 ID 하나(회차 id)이고,
     * 착지 화면은 그 하나로 활동 id를 얻어 사람이 갈 lms 주소를 조립한다 — 활동 id를 함께
     * 요구하면 애초에 부를 수 없는 조회다.
     *
     * **자격을 넓히는 것이 아니다.** 중첩 경로와 같은 «관계자만»(#688)이라 회차 하나로 읽어도
     * 볼 수 있는 사람의 범위는 그대로다. 중첩 경로의 활동 검사는 인가 경계가 아니라 **경로가
     * 가리키는 대상을 못 박는 장치**다 — 그 경로는 화면이 이미 활동을 알고 들어오므로 어긋난 조합을
     * 404로 끊는 것이 맞고, 이 경로는 활동을 모르는 것이 전제라 끊을 것이 없다.
     *
     * 공유 링크를 받은 사람이 관계자가 아니면 여기서 403이다 — 토큰이 주는 것은 미리보기까지다
     * (ADR-0016 · 익명 미리보기는 SessionSharePreviewProvider가 따로 그린다).
     */
    SessionDetailResponse getSessionById(Long sessionId, MemberEntity requester);

    /* 회차 공유 중지의 자격 — 리더·학술국장 (#688 · 프로그램 쪽 requireShareRevocable과 같다) */
    void requireShareRevocable(Long sessionId, MemberEntity requester);

    /* 회차 목록. 활동 상세 화면 안에서 그 활동의 회차만 보는 용도다 — 관계자만 (#688) */
    SessionSearchResponse searchSessions(
            Long academicProgramId, SessionCondition condition, MemberEntity requester);
}
