package org.sscc.ssccopsserver.global.mcp.tool.patch;

import java.time.OffsetDateTime;

import org.sscc.ssccopsserver.domain.operation.dto.SubWorkDetailResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkUpdateRequest;
import org.sscc.ssccopsserver.domain.operation.entity.OperationPriority;

/*
 * 하위 업무 수정 도구의 입력 (ssccops#365 · W1). 전체 교체를 읽고-합치기로 푸는 이유와 예외
 * 근거는 {@link WorkPatch}에 있다 — 같은 규약이다.
 *
 * **하위 업무 유형은 받지 않는다.** 서버 요청 자체가 받지 않고(소급 금지), 바뀌면 승인 필요
 * 여부·승인자·정족수·완료 점검 항목이 통째로 달라진다. 상태·승인도 여기가 아니라 전이 도구다.
 */
public record SubWorkPatch(
        String title,
        Long ownerId,
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        OffsetDateTime dueAt,
        OperationPriority priority,
        String content,
        String completionCriteria,
        String externalLink) {

    /** 상세 조회 값 위에 이 patch의 non-null 필드만 덮어 전체 교체 본문을 만든다. */
    public SubWorkUpdateRequest merge(SubWorkDetailResponse current) {
        return new SubWorkUpdateRequest(
                title != null ? title : current.title(),
                ownerId != null ? ownerId : current.owner().memberId(),
                startAt != null ? startAt : current.startAt(),
                endAt != null ? endAt : current.endAt(),
                dueAt != null ? dueAt : current.dueAt(),
                priority != null ? priority : current.priority(),
                content != null ? content : current.content(),
                completionCriteria != null ? completionCriteria : current.completionCriteria(),
                externalLink != null ? externalLink : current.externalLink());
    }
}
