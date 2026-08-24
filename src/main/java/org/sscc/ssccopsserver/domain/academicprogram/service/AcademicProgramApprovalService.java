package org.sscc.ssccopsserver.domain.academicprogram.service;

import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramApprovalCondition;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramApprovalSearchResponse;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/*
 * 승인 이력 조회 (#139 · 학술관리_API설계.md §3.8). 회차·종료 두 지점의 처리 이력을 한 목록으로
 * 읽는다.
 *
 * 이력을 **쓰는** 쪽(SessionReviewService의 회차 승인 #136, AcademicProgramService의 종료 승인
 * #133)과 서비스를 나눈 것은 자격의 근거가 다르기 때문이다 — 쓰는 쪽은 ACADEMIC_PROGRAM_MANAGE
 * 단일 권한이고, 읽는 쪽은 "이 활동의 스터디장 본인 **또는** 학술국장"이라 활동에 매인 판정이
 * 함께 걸린다. 한 빈에 두면 어느 메서드가 어느 판정을 지고 있는지 호출부에서 읽히지 않는다
 * (SessionService ↔ SessionReviewService를 나눈 것과 같은 이유).
 */
public interface AcademicProgramApprovalService {

    /*
     * 승인 이력 목록(GET /v1/academic-programs/{academicProgramId}/approvals). 없는 활동은
     * 404, 스터디장도 학술국장도 아니면 403이며 그 순서로 검사한다.
     */
    AcademicProgramApprovalSearchResponse getApprovals(
            Long academicProgramId,
            AcademicProgramApprovalCondition condition,
            MemberEntity requester);
}
