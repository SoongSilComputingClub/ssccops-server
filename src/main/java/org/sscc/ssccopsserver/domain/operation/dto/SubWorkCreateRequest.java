package org.sscc.ssccopsserver.domain.operation.dto;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.hibernate.validator.constraints.URL;
import org.sscc.ssccopsserver.domain.operation.entity.OperationPriority;

/*
 * 하위 업무 등록 요청 (OPS-007 · POST /v1/sub-works). 필드 구성은 운영 등록 화면의
 * '하위 업무' 폼을 따른다 — 공통 속성(제목·담당자·시작/종료 일시·우선순위)과
 * 확장 속성(업무 유형·상위 업무·업무 내용·외부 링크)이 그대로 대응한다.
 *
 * 업무 상태·승인 상태·지연 여부·완료 체크리스트는 서버가 정하므로 요청 필드에 없다.
 *
 * 유형을 subWorkTypeId(숫자)로 받는 것은 API 정의서의 itemType(문자 기준 코드)과 다르다.
 * 하위 업무 유형은 승인 정책을 담은 기준 데이터(sub_work_type) 행이고 재배포 없이
 * 추가·변경되어야 하므로(REQ-010·POL-005) enum으로 고정할 수 없다.
 *
 * dueAt(마감 일시)은 화면에 입력란이 없으나 sub_work.ddln_dt에 대응하는 선택 입력으로 받는다.
 * 지연 판정(dly_yn)과 마감 임박 조회(OPS-008)가 이 값에 걸려 있어서, 화면에 없다는 이유로
 * 빼면 그 기능들이 값을 채울 경로를 잃는다.
 */
public record SubWorkCreateRequest(
        @NotNull @Positive Long workId,
        @NotBlank @Size(max = 256) String title,
        @NotNull @Positive Long subWorkTypeId,
        @NotNull @Positive Long ownerId,
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        OffsetDateTime dueAt,
        OperationPriority priority,
        String content,
        @Size(max = 200) @URL String externalLink) {

    // 기간 역전 차단. 한쪽만 주어진 경우는 검사 대상이 아니다 (둘 다 선택 입력)
    @AssertTrue(message = "종료 일시는 시작 일시보다 빠를 수 없습니다.")
    public boolean isPeriodValid() {
        return startAt == null || endAt == null || !endAt.isBefore(startAt);
    }
}
