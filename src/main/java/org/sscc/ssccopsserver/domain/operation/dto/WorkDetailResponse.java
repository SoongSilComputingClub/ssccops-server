package org.sscc.ssccopsserver.domain.operation.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import org.sscc.ssccopsserver.domain.operation.entity.OperationEntity;
import org.sscc.ssccopsserver.domain.operation.entity.OperationPriority;
import org.sscc.ssccopsserver.domain.operation.entity.OperationType;
import org.sscc.ssccopsserver.domain.operation.entity.ProgressRate;
import org.sscc.ssccopsserver.domain.operation.entity.WorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.WorkStatus;
import org.sscc.ssccopsserver.domain.operation.entity.WorkType;

/*
 * 상위 업무 상세 조회 응답 (OPS-003). '업무 상세' 화면 한 장이 필요로 하는 값을 한 번의
 * 호출로 채운다 — 상단 배지·제목·전체 진행률, 좌측 상세 카드, 우측 하위 업무 목록이
 * 모두 여기서 나온다.
 *
 * 화면은 값을 '공통 속성 · operation'과 '확장 속성 · work' 두 블록으로 나눠 보여주지만
 * 응답은 한 단계 평면 구조다. work 테이블에만 있는 값은 workType·workStatus·generalReview
 * 셋뿐이고 확장 블록의 담당자·기간도 실은 oper 컬럼이라, 블록대로 중첩하면 같은 값을
 * 두 번 내리게 된다. 블록 구분은 화면의 몫이다.
 *
 * operationType(oper.oper_type_cd)과 workType(work.work_type_cd)은 화면 라벨이 '운영유형'·
 * '운영 유형'으로 거의 같지만 서로 다른 컬럼이라 필드를 나눠 둔다. 등록 응답
 * (WorkCreateResponse)은 같은 값을 itemType으로 부르는데, 그쪽은 API 정의서 OPS-002의
 * 요청 필드명을 따른 것이라 이름이 갈린다.
 *
 * 기수는 담지 않는다 — 시안에 표기가 있으나 프론트 디자인에서 제외하기로 했고, oper의
 * 기수 컬럼 자체가 데이터사전 개정으로 삭제된 결번이다 (MemberSummaryResponse도 같다).
 *
 * 일시는 AP-12에 따라 Asia/Seoul 오프셋을 포함해 내려준다.
 */
public record WorkDetailResponse(
        Long workId,
        Long operationId,
        OperationType operationType,
        String title,
        WorkType workType,
        WorkStatus workStatus,
        OperationPriority priority,
        MemberSummaryResponse owner,
        MemberSummaryResponse registrant,
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        String generalReview,
        BigDecimal progressRate,
        int subWorkCount,
        List<WorkSubWorkSummaryResponse> subWorks,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    /*
     * 전체 진행률은 저장된 work_prgrs_rt가 아니라 하위 업무 진행률의 평균이다. 시안이
     * 완료 개수 비율이 아니라 평균을 그리고 있어(검토 상태 두 건의 60·80이 상단 70으로
     * 합쳐진다) 이 식을 따른다.
     *
     * 계산 결과를 엔티티에 쓰지 않는 것은 조회가 어떤 상태도 바꾸지 않기 때문이다 (AP-07).
     * 그래서 저장 컬럼과 이 값은 어긋난 채로 남는다 — ProgressRate 주석 참고.
     */
    public static WorkDetailResponse of(
            WorkEntity work, List<WorkSubWorkSummaryResponse> subWorks) {
        OperationEntity operation = work.getOperation();
        List<BigDecimal> rates =
                subWorks.stream().map(WorkSubWorkSummaryResponse::progressRate).toList();

        return new WorkDetailResponse(
                work.getId(),
                operation.getId(),
                operation.getOperationType(),
                operation.getTitle(),
                work.getWorkType(),
                work.getWorkStatus(),
                operation.getPriority(),
                MemberSummaryResponse.from(operation.getPersonInCharge()),
                MemberSummaryResponse.from(operation.getRegistrant()),
                toOffsetDateTime(operation.getBeginAt()),
                toOffsetDateTime(operation.getEndAt()),
                work.getGeneralReview(),
                ProgressRate.average(rates),
                subWorks.size(),
                subWorks,
                toOffsetDateTime(operation.getCreatedAt()),
                toOffsetDateTime(operation.getUpdatedAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
