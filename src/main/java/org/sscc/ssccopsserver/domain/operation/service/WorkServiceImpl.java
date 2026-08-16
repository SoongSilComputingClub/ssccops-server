package org.sscc.ssccopsserver.domain.operation.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.service.MemberService;
import org.sscc.ssccopsserver.domain.operation.code.error.OperationErrorCode;
import org.sscc.ssccopsserver.domain.operation.dto.WorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.WorkCreateResponse;
import org.sscc.ssccopsserver.domain.operation.dto.WorkCursor;
import org.sscc.ssccopsserver.domain.operation.dto.WorkDetailResponse;
import org.sscc.ssccopsserver.domain.operation.dto.WorkListItemResponse;
import org.sscc.ssccopsserver.domain.operation.dto.WorkSearchCondition;
import org.sscc.ssccopsserver.domain.operation.dto.WorkSearchQuery;
import org.sscc.ssccopsserver.domain.operation.dto.WorkSearchResponse;
import org.sscc.ssccopsserver.domain.operation.dto.WorkSubWorkSummaryResponse;
import org.sscc.ssccopsserver.domain.operation.dto.WorkUpdateRequest;
import org.sscc.ssccopsserver.domain.operation.entity.OperationEntity;
import org.sscc.ssccopsserver.domain.operation.entity.OperationPriority;
import org.sscc.ssccopsserver.domain.operation.entity.ProgressRate;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.WorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.WorkStatus;
import org.sscc.ssccopsserver.domain.operation.repository.OperationRepository;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkChecklistItemRepository;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkChecklistProgress;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkRepository;
import org.sscc.ssccopsserver.domain.operation.repository.WorkRepository;
import org.sscc.ssccopsserver.domain.operation.repository.WorkSubWorkAggregate;
import org.sscc.ssccopsserver.global.apipayload.PageResponse;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkServiceImpl implements WorkService {

    private final OperationRepository operationRepository;
    private final WorkRepository workRepository;
    private final SubWorkRepository subWorkRepository;
    private final SubWorkChecklistItemRepository subWorkChecklistItemRepository;
    private final MemberService memberService;

    /*
     * 수정(updateWork)이 mdfcn_dt를 바로 응답에 실어야 해서 필요하다 — @LastModifiedDate는
     * flush 시점(JPA @PreUpdate 콜백)에야 in-memory 엔티티에 채워지므로, flush 없이 바로
     * WorkDetailResponse를 만들면 방금 바꾼 값인데도 updatedAt이 직전 값 그대로 나간다.
     */
    private final EntityManager entityManager;

    /*
     * oper(공통)와 work(확장)를 한 트랜잭션에서 INSERT 한다. 둘 중 하나만 남으면
     * 부모 없는 업무이거나 자식 없는 운영 건이 되므로 경계를 쪼개지 않는다 (AR-11).
     */
    @Override
    @Transactional
    public WorkCreateResponse createWork(WorkCreateRequest request, MemberEntity registrant) {
        // 담당자 실재 여부는 회원 도메인 Service를 경유해 확인한다 (AR-07·LY-10)
        MemberEntity owner =
                memberService
                        .findAssignableMember(request.ownerId())
                        .orElseThrow(
                                () ->
                                        new GeneralException(
                                                OperationErrorCode.OWNER_NOT_ACTIVE_MEMBER));

        Instant beginAt = toInstant(request.startAt());
        Instant endAt = toInstant(request.endAt());
        // DTO의 @AssertTrue가 이미 걸러내지만, Service를 직접 호출하는 경로에서도 성립해야 하는 규칙이다
        validatePeriod(beginAt, endAt);

        OperationEntity operation =
                operationRepository.save(
                        OperationEntity.createForWork(
                                request.title(),
                                registrant,
                                owner,
                                beginAt,
                                endAt,
                                request.priority()));
        WorkEntity work =
                workRepository.save(
                        WorkEntity.create(operation, request.itemType(), request.review()));

        return WorkCreateResponse.from(work);
    }

    /*
     * 상세 조회(OPS-003). 쿼리는 업무 1 + 하위 업무 목록 1 + 체크리스트 집계 1로 3회다 —
     * 하위 업무마다 체크리스트를 세면 그대로 N+1이 된다 (DB-13).
     *
     * 조회는 어떤 상태도 바꾸지 않는다 (AP-07). 진행률도 계산만 하고 work_prgrs_rt에
     * 쓰지 않으므로 저장된 값과 어긋날 수 있다.
     */
    @Override
    public WorkDetailResponse getWork(Long workId) {
        return buildDetail(findWork(workId));
    }

    /*
     * 기본 정보 수정(OPS-004). oper(제목·기간·우선순위·담당자)와 work(업무 유형·총평)를
     * 한 트랜잭션에서 함께 바꾼다 — 등록이 두 행을 함께 만드는 것과 같은 경계다(AR-11).
     *
     * workStatus·진행률은 손대지 않는다. 요청 DTO에 그 필드가 아예 없어(POL-003) 여기서
     * 막을 것도 없다.
     */
    @Override
    @Transactional
    public WorkDetailResponse updateWork(Long workId, WorkUpdateRequest request) {
        WorkEntity work = findWork(workId);

        // 담당자 실재 여부는 등록과 같은 규칙이다 (AR-07·LY-10)
        MemberEntity owner =
                memberService
                        .findAssignableMember(request.ownerId())
                        .orElseThrow(
                                () ->
                                        new GeneralException(
                                                OperationErrorCode.OWNER_NOT_ACTIVE_MEMBER));

        Instant beginAt = toInstant(request.startAt());
        Instant endAt = toInstant(request.endAt());
        validatePeriod(beginAt, endAt);

        OperationEntity operation = work.getOperation();
        operation.changeTitle(request.title());
        operation.changeSchedule(beginAt, endAt);
        // 등록 팩토리(createForWork)와 같은 기본값 — 생략하면 NORMAL이다
        operation.changePriority(orNormalPriority(request.priority()));
        operation.changePersonInCharge(owner);
        work.changeWorkType(request.itemType());
        work.writeGeneralReview(request.review());

        // mdfcn_dt를 지금 바꾼 값으로 응답에 실으려면 flush로 감사 필드를 먼저 채워야 한다
        entityManager.flush();

        return buildDetail(work);
    }

    private WorkEntity findWork(Long workId) {
        return workRepository
                .findByIdAndOperationDeletedAtIsNull(workId)
                .orElseThrow(() -> new GeneralException(OperationErrorCode.WORK_NOT_FOUND));
    }

    private WorkDetailResponse buildDetail(WorkEntity work) {
        List<SubWorkEntity> subWorks = subWorkRepository.findAllByWorkWithOwner(work);
        Map<Long, SubWorkChecklistProgress> progressBySubWorkId = checklistProgressOf(subWorks);

        List<WorkSubWorkSummaryResponse> summaries =
                subWorks.stream().map(subWork -> summarize(subWork, progressBySubWorkId)).toList();

        return WorkDetailResponse.of(work, summaries);
    }

    private OperationPriority orNormalPriority(OperationPriority priority) {
        return priority == null ? OperationPriority.NORMAL : priority;
    }

    /*
     * 목록 조회(OPS-020). 카드 그리드 한 장이 이 호출 하나다.
     *
     * 쿼리는 다섯 번이다 — 목록 · 하위 업무 집계 · 체크리스트 진행률 집계 · 필터 건수 ·
     * 전체 건수. 업무가 몇 건이든, 그 아래 하위 업무가 몇 건이든 이 수는 변하지 않는다
     * (DB-13). 집계는 이번 페이지에 실린 업무에 대해서만 돌리며, 목록이 비거나 하위 업무가
     * 하나도 없으면 그 쿼리는 아예 부르지 않는다 — 빈 컬렉션을 IN에 넘기면 DB에 따라
     * 문법 오류다.
     */
    @Override
    public WorkSearchResponse searchWorks(WorkSearchCondition condition) {
        WorkSearchQuery query = condition.toQuery();

        // 다음 페이지가 있는지 알기 위해 한 건 더 읽어 왔으므로, 남는 한 건은 응답에서 덜어낸다
        List<WorkEntity> fetched = workRepository.search(query);
        boolean hasNext = fetched.size() > query.size();
        List<WorkEntity> rows = hasNext ? fetched.subList(0, query.size()) : fetched;

        Map<Long, List<BigDecimal>> ratesByWorkId = subWorkRatesOf(rows);
        List<WorkListItemResponse> works =
                rows.stream()
                        .map(
                                work ->
                                        WorkListItemResponse.of(
                                                work,
                                                ratesByWorkId.getOrDefault(
                                                        work.getId(), List.of())))
                        .toList();

        PageResponse page =
                new PageResponse(
                        query.size(),
                        query.sort().getParameter(),
                        nextCursorOf(query, rows, hasNext),
                        hasNext,
                        workRepository.countMatching(query),
                        workRepository.countByOperationDeletedAtIsNull());
        return new WorkSearchResponse(works, page);
    }

    /*
     * 운영 통합(OPS-001)의 업무 전량 목록. 커서 페이징만 없을 뿐 카드 한 장의 값은 목록
     * 조회(OPS-020)와 같아야 하므로, 집계(subWorkRatesOf)와 DTO 조립을 그대로 공유한다 —
     * 여기서 산식을 다시 적으면 통합 화면과 업무 화면이 같은 업무를 다른 %로 그린다.
     *
     * 쿼리는 목록 1 + 하위 업무 집계 1 + 체크리스트 진행률 집계 1로 3회이며, 업무·하위
     * 업무가 몇 건이든 이 수는 변하지 않는다 (DB-13).
     */
    @Override
    public List<WorkListItemResponse> listWorks() {
        List<WorkEntity> rows =
                workRepository
                        .findAllByOperationDeletedAtIsNullOrderByOperationCreatedAtDescIdDesc();
        Map<Long, List<BigDecimal>> ratesByWorkId = subWorkRatesOf(rows);
        return rows.stream()
                .map(
                        work ->
                                WorkListItemResponse.of(
                                        work, ratesByWorkId.getOrDefault(work.getId(), List.of())))
                .toList();
    }

    // 다음 커서는 이번 페이지의 마지막 행을 가리킨다. 마지막 페이지면 커서가 없다
    private String nextCursorOf(WorkSearchQuery query, List<WorkEntity> rows, boolean hasNext) {
        return hasNext ? WorkCursor.of(query.sort(), rows.get(rows.size() - 1)).encode() : null;
    }

    /*
     * 이번 페이지에 실린 업무별 하위 업무 진행률 목록. 카드의 진행률(AGG-01)은 이 값들의
     * 평균이고 하위 업무 건수는 이 목록의 크기라, 둘이 같은 집계에서 나와야 분모가 어긋나지
     * 않는다.
     *
     * 하위 업무 진행률(AGG-02)을 SQL로 옮겨 쓰지 않는 것은 '완료면 항목과 무관하게 100'이라는
     * 규칙이 두 곳으로 갈라지기 때문이다. 개수만 받아와 도메인 값(ProgressRate)이 판정한다.
     */
    private Map<Long, List<BigDecimal>> subWorkRatesOf(List<WorkEntity> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        List<WorkSubWorkAggregate> aggregates =
                subWorkRepository.findAggregatesByWorkIds(
                        rows.stream().map(WorkEntity::getId).toList());
        if (aggregates.isEmpty()) {
            return Map.of();
        }

        Map<Long, SubWorkChecklistProgress> progressBySubWorkId =
                checklistProgressByIds(
                        aggregates.stream().map(WorkSubWorkAggregate::getSubWorkId).toList());

        Map<Long, List<BigDecimal>> ratesByWorkId = new LinkedHashMap<>();
        for (WorkSubWorkAggregate aggregate : aggregates) {
            SubWorkChecklistProgress progress = progressBySubWorkId.get(aggregate.getSubWorkId());
            long completedItems = progress == null ? 0L : progress.getCompletedCount();
            long totalItems = progress == null ? 0L : progress.getTotalCount();
            ratesByWorkId
                    .computeIfAbsent(aggregate.getWorkId(), workId -> new ArrayList<>())
                    .add(
                            ProgressRate.ofChecklist(
                                    aggregate.getWorkStatus() == WorkStatus.DONE,
                                    completedItems,
                                    totalItems));
        }
        return ratesByWorkId;
    }

    private Map<Long, SubWorkChecklistProgress> checklistProgressOf(List<SubWorkEntity> subWorks) {
        return checklistProgressByIds(subWorks.stream().map(SubWorkEntity::getId).toList());
    }

    // 이름을 나눈 것은 지우기 전 습관이 아니라 문법 때문이다 — 제네릭이 지워지면 두 List가 같아진다
    private Map<Long, SubWorkChecklistProgress> checklistProgressByIds(List<Long> subWorkIds) {
        if (subWorkIds.isEmpty()) {
            // IN () 은 DB에 따라 문법 오류이므로 애초에 쿼리를 보내지 않는다
            return Map.of();
        }
        return subWorkChecklistItemRepository.findProgressBySubWorkIds(subWorkIds).stream()
                .collect(
                        Collectors.toMap(
                                SubWorkChecklistProgress::getSubWorkId, progress -> progress));
    }

    /*
     * 체크리스트가 한 건도 없는 하위 업무는 집계 결과에 나오지 않는다. 항목이 없다는 사실과
     * 조회되지 않았다는 사실이 같은 뜻이므로 0/0으로 본다 — 진행률 판정은 엔티티가 한다.
     */
    private WorkSubWorkSummaryResponse summarize(
            SubWorkEntity subWork, Map<Long, SubWorkChecklistProgress> progressBySubWorkId) {
        SubWorkChecklistProgress progress = progressBySubWorkId.get(subWork.getId());
        long completedItems = progress == null ? 0L : progress.getCompletedCount();
        long totalItems = progress == null ? 0L : progress.getTotalCount();
        return WorkSubWorkSummaryResponse.of(subWork, completedItems, totalItems);
    }

    private void validatePeriod(Instant beginAt, Instant endAt) {
        if (beginAt != null && endAt != null && endAt.isBefore(beginAt)) {
            throw new GeneralException(OperationErrorCode.INVALID_OPERATION_PERIOD);
        }
    }

    private Instant toInstant(OffsetDateTime dateTime) {
        return dateTime == null ? null : dateTime.toInstant();
    }
}
