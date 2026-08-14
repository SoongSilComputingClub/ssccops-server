package org.sscc.ssccopsserver.domain.operation.dto;

import org.sscc.ssccopsserver.domain.operation.entity.SubWorkTypeEntity;

/*
 * 승인함 카드의 정족수 진행 표시 (OPS-017 · 화면 '정족수 2/3 동의').
 *
 * needed가 false면 단독 유형이라 화면이 진행바를 그리지 않는다 — 시안에서 진행바가 있는 카드와
 * 없는 카드를 가르는 값이 이것이다. 그때 나머지 값은 NULL이다: 0으로 채우면 '0명 중 0명 동의'가
 * 되어 정족수가 있는데 아무도 찬성하지 않은 상태와 구분되지 않는다.
 */
public record ApprovalQuorumResponse(
        boolean needed, Integer requiredCount, Long currentCount, Boolean met) {

    public static ApprovalQuorumResponse of(SubWorkTypeEntity subWorkType, long agreedCount) {
        if (!subWorkType.requiresQuorum()) {
            return new ApprovalQuorumResponse(false, null, null, null);
        }
        int requiredCount = subWorkType.getMinAgreeCount();
        return new ApprovalQuorumResponse(
                true, requiredCount, agreedCount, agreedCount >= requiredCount);
    }
}
