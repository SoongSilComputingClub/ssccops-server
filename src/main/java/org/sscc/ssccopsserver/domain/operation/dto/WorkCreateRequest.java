package org.sscc.ssccopsserver.domain.operation.dto;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.sscc.ssccopsserver.domain.operation.entity.OperationPriority;
import org.sscc.ssccopsserver.domain.operation.entity.WorkType;

/*
 * 업무 등록 요청 (OPS-002 · POST /v1/works). 필드 구성은 운영 등록 화면을 따른다.
 *
 * 등록자·등록시각과 업무 상태·진행률은 서버가 정하므로 요청 필드에 없다.
 * 진행률은 하위 업무가 연결되면 자동 집계되는 값이라 등록 시점에 받지 않는다.
 * 화면에 입력란이 없는 부서·종일 일정·공개범위도 받지 않는다.
 *
 * priority는 화면에서 항상 하나가 선택돼 있으나(기본값 보통), 누락돼도 NORMAL로 저장한다.
 * review(회고 내용)는 "지금은 비워도 됩니다" 안내대로 선택 입력이다.
 *
 * 일시는 AP-12에 따라 오프셋을 포함한 RFC 3339 문자열로 주고받고,
 * 저장 시점에 엔티티의 Instant로 변환한다.
 */
public record WorkCreateRequest(
        @NotBlank @Size(max = 256) String title,
        @NotNull WorkType itemType,
        @NotNull @Positive Long ownerId,
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        OperationPriority priority,
        String review) {

    // 기간 역전 차단. 한쪽만 주어진 경우는 검사 대상이 아니다 (둘 다 선택 입력)
    @AssertTrue(message = "종료 일시는 시작 일시보다 빠를 수 없습니다.")
    public boolean isPeriodValid() {
        return startAt == null || endAt == null || !endAt.isBefore(startAt);
    }
}
