package org.sscc.ssccopsserver.domain.operation.service;

import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.operation.dto.ApprovalInboxResponse;
import org.sscc.ssccopsserver.domain.operation.dto.ApprovalInboxSearchCondition;

public interface ApprovalService {

    /*
     * 승인함을 조회한다 (OPS-017 · REQ-015 · #47). 대기·승인·반려 세 탭이 같은 리소스를
     * 상태로만 갈라 본다.
     *
     * 승인이 필요 없는 유형은 어느 탭에도 나오지 않고, 소프트 삭제된 건도 마찬가지다.
     * 목록을 보는 회원(viewer)은 요청 본문이 아니라 인증 주체에서 오며, 카드에 '내가 던진 표'를
     * 실어 주는 데만 쓴다 — 목록 자체는 승인자별로 다르게 걸러지지 않는다.
     */
    ApprovalInboxResponse searchApprovals(
            ApprovalInboxSearchCondition condition, MemberEntity viewer);
}
