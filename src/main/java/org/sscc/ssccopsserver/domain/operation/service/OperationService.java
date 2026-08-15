package org.sscc.ssccopsserver.domain.operation.service;

import org.sscc.ssccopsserver.domain.operation.dto.OperationHubResponse;

public interface OperationService {

    /*
     * 운영 통합 조회 (OPS-001). 업무·하위 업무·회의 전량을 한 번에 돌려준다 —
     * '운영 통합' 화면의 카드·목록·트리가 전부 이 응답 하나로 그려진다.
     */
    OperationHubResponse getOperationHub();
}
