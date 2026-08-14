package org.sscc.ssccopsserver.domain.operation.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.operation.entity.ApprovalStatus;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkTypeEntity;
import org.sscc.ssccopsserver.domain.operation.entity.WorkStatus;

/*
 * 하위 업무 목록(OPS-008)의 한 행. 화면 표의 일곱 칸 — 하위 업무 · 상위 업무 · 유형 ·
 * 담당자 · 마감 · 상태 · 진행률 — 과 행 클릭 시 상세(OPS-009)로 가는 식별자를 담는다.
 *
 * 상위 업무 상세의 하위 업무 요약(WorkSubWorkSummaryResponse)과 필드가 겹치지만 재사용하지
 * 않는다. 이 목록은 상위 업무를 가로지르므로 상위 업무 제목과 유형명이 더 필요한데, 그쪽에
 * 필드를 늘리면 이미 나간 OPS-003 계약이 바뀌고 거기서는 상위 업무 제목이 화면 상단과
 * 중복되는 값이기도 하다. 목록마다 요약 DTO를 따로 두는 것이 AP-14의 방향이다.
 *
 * 상태 칸은 시안에서 배지 하나('승인 대기' 또는 '진행')지만 서버는 업무 상태와 승인 상태를
 * 각각 내린다. 둘은 별개 축이고(OPS-009도 같다), 어느 쪽을 배지로 보여줄지는 화면의 몫이다.
 *
 * 일시는 AP-12에 따라 Asia/Seoul 오프셋을 포함해 내려준다.
 */
public record SubWorkSummaryResponse(
        Long subWorkId,
        String title,
        WorkSummaryResponse work,
        Long subWorkTypeId,
        String subWorkTypeName,
        MemberSummaryResponse owner,
        WorkStatus workStatus,
        ApprovalStatus approvalStatus,
        BigDecimal progressRate,
        OffsetDateTime dueAt,
        boolean isDelayed) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    /*
     * 진행률은 체크리스트 완료율에서 파생한다 (AGG-02). 목록 전체를 한 번에 집계해 개수만
     * 넘겨받으며, 항목이 하나도 없는 하위 업무는 집계 결과에 나오지 않아 0/0으로 들어온다.
     *
     * delayed도 dly_yn 컬럼이 아니라 조회 시점 판정값이다 — 그 컬럼은 등록 시 false로 고정된
     * 뒤 갱신하는 주체가 없다. 조회가 컬럼을 채우지는 않는다 (AP-07).
     */
    public static SubWorkSummaryResponse of(
            SubWorkEntity subWork, long completedItems, long totalItems, boolean delayed) {
        SubWorkTypeEntity subWorkType = subWork.getSubWorkType();
        return new SubWorkSummaryResponse(
                subWork.getId(),
                subWork.getTitle(),
                WorkSummaryResponse.from(subWork.getWork()),
                subWorkType.getId(),
                subWorkType.getTypeName(),
                MemberSummaryResponse.from(subWork.getOperation().getPersonInCharge()),
                subWork.getWorkStatus(),
                subWork.getApprovalStatus(),
                subWork.progressRate(completedItems, totalItems),
                toOffsetDateTime(subWork.getDueAt()),
                delayed);
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
