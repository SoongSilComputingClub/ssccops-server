package org.sscc.ssccopsserver.domain.academicprogram.dto;

import java.time.LocalDate;

import org.sscc.ssccopsserver.domain.academicprogram.entity.CurriculumItemEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.SessionEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.SessionStatus;

/*
 * 계획(curriculum_item) + 실적(session) 조인 한 줄 (#134, 학술관리_API설계.md §3.3). 활동 상세
 * 화면의 "커리큘럼 대비 진행" 표가 이 배열 하나로 그려진다.
 *
 * sessionSttsCd는 실적 행이 없어도 NOT_SUBMITTED 문자열로 채워 내려간다(설계 결정 #1) —
 * 클라이언트가 null 분기를 갖지 않게 하려는 것이며, 역할·상태를 웹에서 재계산하지 않는다는
 * 원칙의 확장이다. 반대로 sessionId·realDt·cn은 실적이 있을 때만 채운다(없는 것을 있는 척
 * 하지 않는다).
 *
 * isEditable도 서버가 판정해 내린다 — 스터디장/팀장 본인 여부(AcademicProgramOwnershipPolicy)와
 * 회차 상태(SessionStatus.allowsRecording)를 곱한 값이라, 웹이 leadrMbrId === 내 mbrId를 다시
 * 계산하면 버튼과 실제 판정이 갈린다.
 *
 * 팩토리가 둘인 것은 실적 유무가 이 줄의 모양을 가르기 때문이다 — 없으면 상태만 합성하고
 * (withoutSession), 있으면 저장된 상태와 실제 진행일·내용을 그대로 싣는다(withSession, #135).
 */
public record CurriculumItemWithSessionResponse(
        Long curriculumItemId,
        Integer seqno,
        String ttl,
        LocalDate planDt,
        Long sessionId,
        String sessionSttsCd,
        LocalDate realDt,
        String cn,
        boolean isEditable) {

    /*
     * 실적이 없는 계획 한 줄. 상태는 저장된 값이 아니라 "session 행이 없다"는 사실에서 합성된다
     * (데이터모델 §3 — NOT_SUBMITTED는 파생 상태다).
     */
    public static CurriculumItemWithSessionResponse withoutSession(
            CurriculumItemEntity curriculumItem, boolean isLeader) {
        return new CurriculumItemWithSessionResponse(
                curriculumItem.getId(),
                curriculumItem.getSeqno(),
                curriculumItem.getTitle(),
                curriculumItem.getPlanDate(),
                null,
                SessionStatus.NOT_SUBMITTED.name(),
                null,
                null,
                isLeader && SessionStatus.NOT_SUBMITTED.allowsRecording());
    }

    /*
     * 실적이 붙은 계획 한 줄(#135). 상태는 합성하지 않고 저장된 값을 그대로 쓴다 — 여기서
     * NOT_SUBMITTED가 나올 수 없는 것은 session 행이 있다는 사실 자체가 그 상태를 부정하기
     * 때문이다.
     *
     * isEditable은 여전히 두 조건의 곱이다. 상태 쪽 판정을 SessionStatus.allowsRecording에
     * 맡기므로 SUBMITTED·APPROVED인 회차의 버튼은 스터디장 본인에게도 꺼진다.
     */
    public static CurriculumItemWithSessionResponse withSession(
            CurriculumItemEntity curriculumItem, SessionEntity session, boolean isLeader) {
        return new CurriculumItemWithSessionResponse(
                curriculumItem.getId(),
                curriculumItem.getSeqno(),
                curriculumItem.getTitle(),
                curriculumItem.getPlanDate(),
                session.getId(),
                session.getStatus().name(),
                session.getRealDate(),
                session.getContent(),
                isLeader && session.getStatus().allowsRecording());
    }
}
