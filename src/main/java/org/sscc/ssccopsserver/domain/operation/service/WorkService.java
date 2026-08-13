package org.sscc.ssccopsserver.domain.operation.service;

import org.sscc.ssccopsserver.domain.operation.dto.WorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.WorkCreateResponse;

public interface WorkService {

    // 업무를 등록한다 (OPS-002). oper와 work 두 행이 한 트랜잭션에서 생성된다.
    WorkCreateResponse createWork(WorkCreateRequest request);
}
