package org.sscc.ssccopsserver.domain.operation.dto;

import org.sscc.ssccopsserver.domain.operation.entity.OperationEntity;
import org.sscc.ssccopsserver.domain.operation.entity.OperationType;

/*
 * 안건이 연결한 운영 건 요약 (업무 · 하위 업무 · 회의). 화면이 안건 카드에서 배지·제목만
 * 그리므로 회의 상세(OPS-025)와 같은 형태로 나눠 오는 담당자·상태까지는 담지 않는다 —
 * 그 값이 필요하면 targetId로 해당 상세를 연다.
 *
 * targetId는 operationType에 맞는 상세 ID다 — WORK면 work_id, SUB_WORK면 sub_work_id, MEETING이면
 * mtg_id (#635 · ssccops#575). 상세 주소는 운영 ID(oper_id)가 아니라 이 값을 받는다 — 운영 ID로
 * 업무 상세를 열어 같은 숫자의 다른 업무가 열렸다. 필드를 더한 것이라 api-compat 게이트를 지난다.
 */
public record AgendaTargetOperationResponse(
        Long operationId, OperationType operationType, String title, Long targetId) {

    public static AgendaTargetOperationResponse from(OperationEntity operation, Long targetId) {
        return operation == null
                ? null
                : new AgendaTargetOperationResponse(
                        operation.getId(),
                        operation.getOperationType(),
                        operation.getTitle(),
                        targetId);
    }
}
