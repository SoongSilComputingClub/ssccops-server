package org.sscc.ssccopsserver.domain.operation.service;

import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.operation.dto.DashboardResponse;

public interface DashboardService {

    /*
     * 운영 대시보드를 조회한다 (OPS-038). 승인함·하위 업무 조회 서비스가 이미 갖고 있는
     * 규칙을 그대로 불러 요약할 뿐, 이 서비스 자체는 새 집계 규칙을 만들지 않는다.
     *
     * viewer는 '내 업무 목록'을 좁히는 데만 쓴다 — 승인 대기·다가오는 마감은 보는 사람과
     * 무관하게 같은 값이다(승인함이 운영진 전체에게 같은 목록을 보여주는 것과 같은 이유).
     */
    DashboardResponse getDashboard(MemberEntity viewer);
}
