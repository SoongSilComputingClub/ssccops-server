package org.sscc.ssccopsserver.global.mcp.tool.patch;

import java.time.OffsetDateTime;

import org.sscc.ssccopsserver.domain.operation.dto.WorkDetailResponse;
import org.sscc.ssccopsserver.domain.operation.dto.WorkUpdateRequest;
import org.sscc.ssccopsserver.domain.operation.entity.OperationPriority;
import org.sscc.ssccopsserver.domain.operation.entity.WorkType;

/*
 * 업무 수정 도구의 입력 (ssccops#365 · W1).
 *
 * **서버의 PATCH는 전체 교체다** — 보내지 않은 필드는 지운 것으로 본다(분석 문서 F2). 모델이
 * «마감일만 바꿔»로 부르면 총평·기간이 지워진다. 그래서 도구는 **바꿀 것만** 이 record로 받고,
 * 나머지는 상세 조회 값으로 채워 전체 본문을 만든다(`merge`) — 웹 수정 화면이 하는 일과 같다.
 *
 * **이 record가 ADR-0027의 «도구용 DTO를 쓰지 않는다»에 대한 예외인 이유**는 표현할 수 없는 것을
 * 표현하기 때문이다: `WorkUpdateRequest`는 제목·유형·담당자가 `@NotNull`이라 «안 바꿈»을 담을 수
 * 없다. 그래서 필드는 전부 nullable이고 **이름은 `WorkUpdateRequest`와 1:1로 맞춘다** —
 * `McpPatchContractTest`가 그 대응을 반사로 검사해, 컨트롤러 record에 필드가 늘면 테스트가 깨진다.
 *
 * 대가: 읽고-쓰기 사이에 남이 고친 값을 되돌릴 수 있다(덮어쓰기 경합). 도구 설명에 밝히고,
 * 상태·승인처럼 전이 API가 따로 있는 값은 여기서 받지 않는다.
 */
public record WorkPatch(
        String title,
        WorkType itemType,
        Long ownerId,
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        OperationPriority priority,
        String review) {

    /** 상세 조회 값 위에 이 patch의 non-null 필드만 덮어 전체 교체 본문을 만든다. */
    public WorkUpdateRequest merge(WorkDetailResponse current) {
        return new WorkUpdateRequest(
                title != null ? title : current.title(),
                itemType != null ? itemType : current.workType(),
                ownerId != null ? ownerId : current.owner().memberId(),
                startAt != null ? startAt : current.startAt(),
                endAt != null ? endAt : current.endAt(),
                priority != null ? priority : current.priority(),
                review != null ? review : current.generalReview());
    }
}
