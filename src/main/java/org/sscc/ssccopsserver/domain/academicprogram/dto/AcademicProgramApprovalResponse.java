package org.sscc.ssccopsserver.domain.academicprogram.dto;

import java.time.Instant;

import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramApprovalEntity;
import org.sscc.ssccopsserver.domain.academicprogram.entity.SessionEntity;

/*
 * 승인 이력 한 줄 (#139 · 학술관리_API설계.md §3.8). 활동 상세의 "처리 이력"과 스터디장
 * 대시보드의 "내 제출 처리 현황"이 같은 배열을 쓴다.
 *
 * **이 응답에만 사유(opnnCn)가 실린다.** 그래서 열람 범위가 "인증만"이 아니라 스터디장 본인
 * 또는 학술국장이다 — 수정요청 사유는 활동 운영진 개인에게 민감할 수 있다(2026-08-22 2차 검증
 * 확정). 상태값만 필요한 화면은 커리큘럼 조회(#134)의 sesnSttsCd로 이미 전원에게 열려
 * 있으므로 이 제한이 그쪽을 막지는 않는다.
 *
 * sessionId는 SESSION 지점에만 있다 — COMPLETION은 활동 단위 승인이라 언제나 null이다
 * (데이터모델 §2). 회차 주제·번호를 함께 싣지 않는 것은 이 목록이 "무엇이 처리됐는가"가 아니라
 * "언제 누가 어떻게 처리했는가"의 표이고, 회차의 내용은 회차 상세(#135)가 갖기 때문이다.
 *
 * aprvDt는 처리 일시이며 PENDING이면 null이다. 지금 이 테이블에 PENDING 행을 쌓는 경로는
 * 없지만(AcademicProgramApprovalStatus 주석) 그 사실은 이 DTO가 아니라 쓰는 쪽의 성질이라
 * null 아님을 전제하지 않는다.
 */
public record AcademicProgramApprovalResponse(
        Long approvalId,
        String aprvSeCd,
        String aprvSttsCd,
        Long sessionId,
        String aprvrMbrNm,
        String opnnCn,
        Instant aprvDt) {

    public static AcademicProgramApprovalResponse of(AcademicProgramApprovalEntity approval) {
        SessionEntity session = approval.getSession();

        return new AcademicProgramApprovalResponse(
                approval.getId(),
                approval.getPoint().name(),
                approval.getStatus().name(),
                session == null ? null : session.getId(),
                approval.getApprover().getName(),
                approval.getOpinionContent(),
                approval.getApprovedAt());
    }
}
