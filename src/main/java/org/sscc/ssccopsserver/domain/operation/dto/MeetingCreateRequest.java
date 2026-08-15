package org.sscc.ssccopsserver.domain.operation.dto;

import java.time.OffsetDateTime;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.sscc.ssccopsserver.domain.operation.entity.AttendeeScope;
import org.sscc.ssccopsserver.domain.operation.entity.MeetingCategory;
import org.sscc.ssccopsserver.domain.operation.entity.OperationPriority;

/*
 * 회의 등록 요청 (OPS-024 · POST /v1/meetings). 필드 구성은 운영 등록 화면(회의)을 따른다.
 *
 * chairpersonId(의장·책임자)는 요청에 없다 — 회의 책임자는 항상 담당자(personInChargeId)와
 * 동일 인물이며, 화면도 별도 입력란을 두지 않기로 했다(ssccops-web#56 이슈 본문). 서비스가
 * personInChargeId를 oper.pic_id·mtg.mtg_rbprsn_id 양쪽에 채운다.
 *
 * 정의서 원안은 TOPIC 회의에 한해 단수 targetOperationId를 받았으나, 실제 등록 화면은
 * 회의 구분과 무관하게 안건을 여러 건 함께 입력할 수 있어(operation-create-page.tsx의
 * agenda[] 상태) agendas 배열로 넓혔다 — 등록 뒤 안건 상정(OPS-027)을 여러 번 부르지 않고
 * 한 번에 끝낸다. 목록이 비어 있어도 등록 자체는 막지 않는다(안건 없는 회의도 정상이다).
 *
 * 등록자·회의 상태는 서버가 정하므로 요청 필드에 없다(회의 상태는 항상 SCHEDULED로 시작).
 * 일시는 AP-12에 따라 오프셋을 포함한 RFC 3339 문자열로 받는다.
 */
public record MeetingCreateRequest(
        @NotBlank @Size(max = 200) String title,
        @NotNull MeetingCategory meetingCategory,
        @NotNull @Positive Long personInChargeId,
        @NotNull OffsetDateTime startAt,
        OffsetDateTime endAt,
        OperationPriority priority,
        AttendeeScope attendeeScope,
        @Size(max = 100) String location,
        @Valid List<MeetingAgendaItemRequest> agendas) {

    // 기간 역전 차단. 한쪽만 주어진 경우는 검사 대상이 아니다 (WorkCreateRequest와 같은 규칙)
    @AssertTrue(message = "종료 일시는 시작 일시보다 빠를 수 없습니다.")
    public boolean isPeriodValid() {
        return startAt == null || endAt == null || !endAt.isBefore(startAt);
    }
}
