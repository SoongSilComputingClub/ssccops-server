package org.sscc.ssccopsserver.domain.operation.repository;

import org.sscc.ssccopsserver.domain.operation.entity.OperationType;

/*
 * 운영 건(oper_id)별 유형 상세 ID 프로젝션 (#635 · ssccops#575). 운영 건은 업무·하위 업무·회의 중
 * 정확히 하나에 딸리므로 세 값 중 하나만 채워지고 나머지는 null이다. 어느 것을 쓸지는 운영
 * 유형이 고른다(idFor) — OperationRepository.findDetailIdsByOperationIds 참고.
 */
public interface OperationDetailIds {

    Long getOperationId();

    Long getWorkId();

    Long getSubWorkId();

    Long getMeetingId();

    default Long idFor(OperationType operationType) {
        return switch (operationType) {
            case WORK -> getWorkId();
            case SUB_WORK -> getSubWorkId();
            case MEETING -> getMeetingId();
        };
    }
}
