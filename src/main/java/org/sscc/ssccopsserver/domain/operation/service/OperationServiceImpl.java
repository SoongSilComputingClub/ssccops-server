package org.sscc.ssccopsserver.domain.operation.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.operation.dto.OperationHubResponse;

import lombok.RequiredArgsConstructor;

/*
 * 운영 통합 조회 (OPS-001 · ssccops-web#63).
 *
 * 세 배열 모두 각 도메인 서비스의 목록 조회를 그대로 불러 조립하기만 한다 — 업무 카드는
 * 목록 조회(OPS-020)의 요약을, 하위 업무 행은 목록 조회(OPS-008)의 집계(AGG-02)를, 회의
 * 카드는 회의 목록(OPS-031)을 쓴다. 여기서 필터·정렬·집계 규칙을 새로 만들면 통합 화면과
 * 각 도메인 화면이 같은 건을 다르게 그릴 수 있다(DashboardServiceImpl과 같은 판단).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OperationServiceImpl implements OperationService {

    private final WorkService workService;
    private final SubWorkService subWorkService;
    private final MeetingService meetingService;

    @Override
    public OperationHubResponse getOperationHub() {
        return new OperationHubResponse(
                workService.listWorks(),
                subWorkService.listSubWorks(),
                meetingService.listMeetings());
    }
}
