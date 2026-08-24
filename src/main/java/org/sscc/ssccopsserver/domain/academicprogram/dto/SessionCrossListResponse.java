package org.sscc.ssccopsserver.domain.academicprogram.dto;

import java.time.LocalDate;

import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.CurriculumItemEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.SessionEntity;
import org.sscc.ssccopsserver.domain.academicprogram.repository.SessionAttendanceCount;

/*
 * 활동 횡단 회차 목록(#136) 한 줄. 학술국장의 "회차 이력"(GET /v1/academic-programs/sessions)과
 * "회차·출석 승인"(GET /v1/academic-programs/reviews/sessions) 두 화면이 이 DTO를 공유한다 —
 * 후자는 전자에 sttsCd = SUBMITTED가 고정된 특수형이라, 응답 모양까지 다르면 같은 표를 그리는
 * 컴포넌트가 두 벌이 된다(설계 결정 #2).
 *
 * 활동 상세 안의 회차 목록(SessionSummaryResponse)과 DTO를 나눈 것은 실리는 값이 반대이기
 * 때문이다 — 이쪽은 어느 활동의 회차인지(academicProgramTitle·typeCd)를 반드시 알려야 하고,
 * 대신 작성자(rgtrMbrNm)·계획일은 싣지 않는다. 이 화면에서 고르는 기준은 "누가 썼는가"가
 * 아니라 "어느 활동의 몇 회차인가"다.
 *
 * 진행 내용(cn)·전달사항·출석부 명단은 여기서도 싣지 않는다 — 회차 수만큼 TEXT가 따라 나온다
 * (SessionSummaryResponse와 같은 판단).
 */
public record SessionCrossListResponse(
        Long sessionId,
        Long academicProgramId,
        String academicProgramTitle,
        String typeCd,
        Integer seqno,
        String curriculumTtl,
        LocalDate realDt,
        String sttsCd,
        long presentCount,
        long totalCount,
        boolean hasFileReference) {

    /*
     * hasFileReference는 아직 언제나 false다 — file_reference 테이블·엔티티를 #137이 만든다
     * (SessionFileReferenceResponse 주석과 같은 자리). 계약에 자리를 비워 두는 것은 승인 화면이
     * "인증사진이 붙었는가"를 목록에서 한눈에 봐야 하기 때문이고, 값을 만들어 내지 않고 없는
     * 대로 내린다.
     *
     * 출석 집계가 없는 회차(출석부가 빈 회차)는 0/0이다 — 집계 쿼리 결과에 아예 나오지 않는다.
     */
    public static SessionCrossListResponse of(SessionEntity session, SessionAttendanceCount count) {
        CurriculumItemEntity curriculumItem = session.getCurriculumItem();
        AcademicProgramEntity academicProgram = curriculumItem.getAcademicProgram();

        return new SessionCrossListResponse(
                session.getId(),
                academicProgram.getId(),
                academicProgram.getEvent().getTitle(),
                academicProgram.getType().getCode(),
                curriculumItem.getSeqno(),
                curriculumItem.getTitle(),
                session.getRealDate(),
                session.getStatus().name(),
                count == null ? 0 : count.getPresentCount(),
                count == null ? 0 : count.getTotalCount(),
                false);
    }
}
