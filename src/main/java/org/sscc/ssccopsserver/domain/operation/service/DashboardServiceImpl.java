package org.sscc.ssccopsserver.domain.operation.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.operation.dto.ApprovalInboxItemResponse;
import org.sscc.ssccopsserver.domain.operation.dto.ApprovalInboxSearchCondition;
import org.sscc.ssccopsserver.domain.operation.dto.DashboardResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkSummaryResponse;

import lombok.RequiredArgsConstructor;

/*
 * 운영 대시보드 (OPS-038 · ssccops-web#60).
 *
 * 세 영역 모두 다른 서비스의 조회를 그대로 불러 조립하기만 한다 — 승인 대기는 승인함(OPS-017)의
 * 대기 탭, 다가오는 마감·내 업무는 하위 업무 목록(OPS-008)이 쓰는 것과 같은 집계(AGG-02)다.
 * 여기서 필터·정렬 규칙을 새로 만들면 화면과 대시보드가 같은 값을 다르게 셀 수 있다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardServiceImpl implements DashboardService {

    /*
     * 승인 대기 카드는 미리보기다 — '전체보기'가 승인함(OPS-017)으로 이동한다(OPS-SCR-001).
     * 승인함과 같은 정렬(마감 오름차순, 마감 없는 건은 뒤)에서 앞쪽 5건만 싣는다.
     */
    private static final int PENDING_APPROVAL_PREVIEW_SIZE = 5;

    private final ApprovalService approvalService;
    private final SubWorkService subWorkService;

    @Override
    public DashboardResponse getDashboard(MemberEntity viewer) {
        List<ApprovalInboxItemResponse> pendingApproval =
                approvalService
                        .searchApprovals(
                                new ApprovalInboxSearchCondition(
                                        null, PENDING_APPROVAL_PREVIEW_SIZE, null),
                                viewer)
                        .approvals();

        List<SubWorkSummaryResponse> upcomingDeadlines = subWorkService.findUpcomingDeadlines();
        List<SubWorkSummaryResponse> myTasks = subWorkService.findMyTasks(viewer);

        return new DashboardResponse(pendingApproval, upcomingDeadlines, myTasks);
    }
}
