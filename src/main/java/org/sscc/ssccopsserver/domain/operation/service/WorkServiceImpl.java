package org.sscc.ssccopsserver.domain.operation.service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.service.MemberService;
import org.sscc.ssccopsserver.domain.operation.code.error.OperationErrorCode;
import org.sscc.ssccopsserver.domain.operation.dto.WorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.WorkCreateResponse;
import org.sscc.ssccopsserver.domain.operation.dto.WorkDetailResponse;
import org.sscc.ssccopsserver.domain.operation.dto.WorkSubWorkSummaryResponse;
import org.sscc.ssccopsserver.domain.operation.entity.OperationEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.WorkEntity;
import org.sscc.ssccopsserver.domain.operation.repository.OperationRepository;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkChecklistItemRepository;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkChecklistProgress;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkRepository;
import org.sscc.ssccopsserver.domain.operation.repository.WorkRepository;
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
        WorkEntity work =
                workRepository
                        .findByIdAndOperationDeletedAtIsNull(workId)
                        .orElseThrow(() -> new GeneralException(OperationErrorCode.WORK_NOT_FOUND));

        List<SubWorkEntity> subWorks = subWorkRepository.findAllByWorkWithOwner(work);
        Map<Long, SubWorkChecklistProgress> progressBySubWorkId = checklistProgressOf(subWorks);

        List<WorkSubWorkSummaryResponse> summaries =
                subWorks.stream().map(subWork -> summarize(subWork, progressBySubWorkId)).toList();

        return WorkDetailResponse.of(work, summaries);
    }

    private Map<Long, SubWorkChecklistProgress> checklistProgressOf(List<SubWorkEntity> subWorks) {
        if (subWorks.isEmpty()) {
            // IN () 은 DB에 따라 문법 오류이므로 애초에 쿼리를 보내지 않는다
            return Map.of();
        }
        List<Long> subWorkIds = subWorks.stream().map(SubWorkEntity::getId).toList();
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
