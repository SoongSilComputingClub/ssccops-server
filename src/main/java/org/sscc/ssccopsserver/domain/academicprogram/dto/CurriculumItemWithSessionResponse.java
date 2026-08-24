package org.sscc.ssccopsserver.domain.academicprogram.dto;

import java.time.LocalDate;

import org.sscc.ssccopsserver.domain.academicprogram.entity.CurriculumItemEntity;
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
 * Session 엔티티는 아직 없다(#135) — 그래서 지금 실제로 쓰이는 팩토리는 withoutSession 하나뿐이고
 * 모든 줄이 NOT_SUBMITTED로 나온다. 실적을 실어 내리는 팩토리는 엔티티가 생기는 그 이슈가 더한다.
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
}
