package org.sscc.ssccopsserver.domain.academicprogram.dto;

import java.time.LocalDate;

import org.sscc.ssccopsserver.domain.academicprogram.entity.CurriculumItemEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.SessionEntity;
import org.sscc.ssccopsserver.domain.academicprogram.repository.SessionAttendanceCount;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/*
 * 회차 목록(#135 · GET .../sessions) 한 줄. 활동 상세 화면 안에서 그 활동의 회차만 보는
 * 용도라 활동 이름을 다시 싣지 않는다 — 활동을 가로지르는 국장용 목록(학술관리_API설계.md
 * §3.4의 루트 레벨 /v1/academic-programs/sessions)은 별도 DTO를 쓰는 다른 화면이다.
 *
 * 진행 내용(prgrsCn)·전달사항·출석부 명단은 싣지 않는다. 목록에서 필요한 것은 "몇 회차가 언제
 * 열렸고 몇 명이 왔는가"이고, 본문까지 실으면 회차 수만큼 TEXT가 따라 나온다(폼 응답 목록이
 * rspnsCn을 싣지 않는 것과 같은 판단).
 *
 * fileReference 유무를 싣지 않는 것은 file_rfrnc 테이블이 아직 없어서다(#137).
 */
public record SessionSummaryResponse(
        Long sessionId,
        Long curriculumItemId,
        Integer seqno,
        String curriculumTtl,
        LocalDate planYmd,
        LocalDate actlYmd,
        String sttsCd,
        Long rgtrMbrId,
        String rgtrMbrNm,
        long presentCount,
        long totalCount) {

    /** 출석 집계가 없는 회차(출석부가 빈 회차)는 0/0이다 — 집계 쿼리 결과에 아예 나오지 않는다 */
    public static SessionSummaryResponse of(SessionEntity session, SessionAttendanceCount count) {
        CurriculumItemEntity curriculumItem = session.getCurriculumItem();
        MemberEntity registrant = session.getRegistrant();

        return new SessionSummaryResponse(
                session.getId(),
                curriculumItem.getId(),
                curriculumItem.getSeqno(),
                curriculumItem.getTitle(),
                curriculumItem.getPlanDate(),
                session.getRealDate(),
                session.getStatus().name(),
                registrant.getId(),
                registrant.getName(),
                count == null ? 0 : count.getPresentCount(),
                count == null ? 0 : count.getTotalCount());
    }
}
