package org.sscc.ssccopsserver.domain.operation.dto;

import java.time.Instant;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/*
 * 승인함 조회 조건 (OPS-017 · GET /v1/approvals).
 *
 * 필터가 탭 하나뿐이라 하위 업무 목록(OPS-008)의 조건을 그대로 쓰지 않는다 — 마감·지연 필터를
 * 열어 두면 승인함이 목록 화면의 복제가 되고, 두 API가 같은 화면을 두고 갈린다.
 *
 * 정렬은 서버가 마감 오름차순으로 고정한다(AGG-04와 같은 규칙, 마감 없는 건은 뒤). 승인함은
 * '지금 처리해야 하는 순서'로 보는 화면이라 마감이 그 순서이고, 시안에도 정렬 컨트롤이 없다.
 * 검토요청이 오래된 순은 정렬 키가 다른 테이블(sub_work_stts_hstry)에 있어 커서 비교식을
 * 세울 수 없다 — 필요해지면 sub_work에 검토요청 일시를 두는 것이 먼저다.
 */
public record ApprovalInboxSearchCondition(
        String status,
        @Min(value = 1, message = "size는 1 이상이어야 합니다.")
                @Max(
                        value = SubWorkSearchCondition.MAX_SIZE,
                        message = "size는 " + SubWorkSearchCondition.MAX_SIZE + " 이하여야 합니다.")
                Integer size,
        String cursor) {

    // 페이징 기본값·상한은 하위 업무 목록과 같다 (AP-13)
    public ApprovalInboxTab tab() {
        return ApprovalInboxTab.from(status);
    }

    public SubWorkSearchQuery toQuery(Instant now) {
        ApprovalInboxTab tab = tab();
        return new SubWorkSearchQuery(
                tab.getWorkStatus(),
                tab.getApprovalStatuses(),
                false,
                null,
                now,
                size == null ? SubWorkSearchCondition.DEFAULT_SIZE : size,
                SubWorkSortOrder.DEFAULT,
                SubWorkCursor.decode(cursor, SubWorkSortOrder.DEFAULT));
    }
}
