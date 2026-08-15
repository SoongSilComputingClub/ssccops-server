package org.sscc.ssccopsserver.domain.operation.dto;

import java.util.List;

/*
 * 운영 대시보드 (OPS-038 · REQ-024 · ssccops-web#60). 화면 진입 한 번으로 세 영역을 채운다.
 *
 * pendingApproval이 첫 필드인 것은 명세의 일부다(응답의 최상단이 승인 대기다, OPS-SCR-001).
 * 승인함(OPS-017)의 대기 탭 목록을 그대로 재사용한 미리보기이며, 전체는 그 화면에서 본다
 * (ApprovalController 주석 — "대시보드(OPS-038)도 같은 목록을 요약해 쓴다").
 *
 * upcomingDeadlines·myTasks는 하위 업무 목록(OPS-008)과 같은 SubWorkSummaryResponse를 쓴다 —
 * 진행률·지연 여부 계산 규칙(AGG-02)이 하나뿐이어야 목록과 대시보드가 같은 값을 보여준다.
 */
public record DashboardResponse(
        List<ApprovalInboxItemResponse> pendingApproval,
        List<SubWorkSummaryResponse> upcomingDeadlines,
        List<SubWorkSummaryResponse> myTasks) {}
