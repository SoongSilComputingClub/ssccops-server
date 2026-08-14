package org.sscc.ssccopsserver.domain.operation.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import org.sscc.ssccopsserver.domain.operation.entity.OperationEntity;
import org.sscc.ssccopsserver.domain.operation.entity.ProgressRate;
import org.sscc.ssccopsserver.domain.operation.entity.WorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.WorkStatus;
import org.sscc.ssccopsserver.domain.operation.entity.WorkType;

/*
 * 상위 업무 목록(OPS-020)의 카드 한 장. 시안의 카드가 그리는 값 — 상태·유형 배지, 하위 업무
 * 건수, 제목, 담당자, 기간, 진행바 — 와 카드 클릭 시 상세(OPS-003)로 가는 식별자를 담는다.
 *
 * 이름이 WorkSummaryResponse가 아닌 것은 그 이름이 이미 하위 업무 목록(OPS-008)의 '상위 업무'
 * 배지용으로 쓰이고 있기 때문이다. 그쪽은 workId·title 둘뿐이라 여기에 필드를 채워 넣으면
 * 이미 나간 OPS-008 응답이 함께 부풀어 오른다.
 *
 * operationId·priority·generalReview는 담지 않는다. 카드가 쓰지 않는 값이고 목록에는 요약만
 * 둔다 (AP-14) — 필요해지면 상세(OPS-003)가 이미 전부 내린다.
 *
 * 일시는 AP-12에 따라 Asia/Seoul 오프셋을 포함해 내려준다.
 */
public record WorkListItemResponse(
        Long workId,
        String title,
        WorkType workType,
        WorkStatus workStatus,
        MemberSummaryResponse owner,
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        BigDecimal progressRate,
        int subWorkCount) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    /*
     * 진행률은 저장된 work_prgrs_rt가 아니라 하위 업무 진행률의 평균이다 (AGG-01) —
     * 상세 조회(OPS-003)와 같은 산식이라야 같은 업무가 목록과 상세에서 다른 %로 보이지 않는다.
     * 계산 결과를 엔티티에 쓰지 않는 것은 조회가 어떤 상태도 바꾸지 않기 때문이다 (AP-07·AGG-05).
     *
     * 하위 업무 건수와 진행률을 같은 목록에서 뽑는 것은 둘의 분모를 하나로 묶기 위해서다.
     * 소프트 삭제된 하위 업무를 거르는 일은 이 목록을 만드는 쪽이 이미 끝냈다 (AGG-03).
     */
    public static WorkListItemResponse of(WorkEntity work, List<BigDecimal> subWorkRates) {
        OperationEntity operation = work.getOperation();
        return new WorkListItemResponse(
                work.getId(),
                operation.getTitle(),
                work.getWorkType(),
                work.getWorkStatus(),
                MemberSummaryResponse.from(operation.getPersonInCharge()),
                toOffsetDateTime(operation.getBeginAt()),
                toOffsetDateTime(operation.getEndAt()),
                ProgressRate.average(subWorkRates),
                subWorkRates.size());
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
