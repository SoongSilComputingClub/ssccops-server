package org.sscc.ssccopsserver.domain.operation.dto;

import org.sscc.ssccopsserver.domain.operation.entity.OperationEntity;
import org.sscc.ssccopsserver.domain.operation.entity.OperationType;

/*
 * 안건이 연결한 운영 건 요약 (업무 또는 하위 업무). 화면이 안건 카드에서 배지·제목만
 * 그리므로 회의 상세(OPS-025)와 같은 형태로 나눠 오는 담당자·상태까지는 담지 않는다 —
 * 그 값이 필요하면 operationId로 해당 업무·하위 업무 상세를 연다.
 */
public record AgendaTargetOperationResponse(
        Long operationId, OperationType operationType, String title) {

    public static AgendaTargetOperationResponse from(OperationEntity operation) {
        return operation == null
                ? null
                : new AgendaTargetOperationResponse(
                        operation.getId(), operation.getOperationType(), operation.getTitle());
    }
}
