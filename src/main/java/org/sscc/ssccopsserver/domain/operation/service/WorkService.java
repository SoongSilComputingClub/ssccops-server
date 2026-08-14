package org.sscc.ssccopsserver.domain.operation.service;

import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.operation.dto.WorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.WorkCreateResponse;
import org.sscc.ssccopsserver.domain.operation.dto.WorkDetailResponse;

public interface WorkService {

    /*
     * 업무를 등록한다 (OPS-002). oper와 work 두 행이 한 트랜잭션에서 생성된다.
     * registrant는 인증 주체(등록자)이며 요청 본문이 아니라 토큰에서 온다 (LY-05).
     */
    WorkCreateResponse createWork(WorkCreateRequest request, MemberEntity registrant);

    /*
     * 업무 한 건과 그 하위 업무 목록을 함께 조회한다 (OPS-003).
     * 소프트 삭제된 업무는 없는 것으로 보고 WORK_NOT_FOUND를 던진다.
     */
    WorkDetailResponse getWork(Long workId);
}
