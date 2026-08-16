package org.sscc.ssccopsserver.domain.operation.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.service.AuthorityPolicy;
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
 *
 * 컨트롤러는 WORK_READ 하나로만 걸려 있어(#101) 국원도 들어온다. 그런데 승인 대기는 승인함과
 * 같은 데이터라 WORK_MANAGE 없는 조회자에게 그대로 내리면 승인함 자체는 못 보면서 그 내용만
 * 대시보드로 새어 나간다 — 그래서 이 영역만 WORK_MANAGE 보유 여부로 따로 가린다. 내 업무는
 * 원래도 본인 스코프라 그대로 두지만, 다가오는 마감은 전체 스코프라 WORK_MANAGE 없는 조회자
 * (국원)에게는 자신이 담당자인 것만 좁혀서 보여준다 — WORK_READ만으로 하위 업무 목록
 * 전체(GET /v1/sub-works)는 어차피 볼 수 있지만, 대시보드 미리보기는 "내 것" 중심 화면이라
 * 남의 마감까지 요약해 보여줄 이유가 없다.
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
    private final AuthorityPolicy authorityPolicy;

    @Override
    public DashboardResponse getDashboard(MemberEntity viewer) {
        boolean canManageWork =
                authorityPolicy.hasAuthority(viewer.getId(), AuthorityCode.WORK_MANAGE);

        List<ApprovalInboxItemResponse> pendingApproval = pendingApprovalOf(viewer, canManageWork);
        List<SubWorkSummaryResponse> upcomingDeadlines =
                subWorkService.findUpcomingDeadlines(canManageWork ? null : viewer.getId());
        List<SubWorkSummaryResponse> myTasks = subWorkService.findMyTasks(viewer);

        return new DashboardResponse(pendingApproval, upcomingDeadlines, myTasks);
    }

    private List<ApprovalInboxItemResponse> pendingApprovalOf(
            MemberEntity viewer, boolean canManageWork) {
        if (!canManageWork) {
            return List.of();
        }
        return approvalService
                .searchApprovals(
                        new ApprovalInboxSearchCondition(null, PENDING_APPROVAL_PREVIEW_SIZE, null),
                        viewer)
                .approvals();
    }
}
