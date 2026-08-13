package org.sscc.ssccopsserver.domain.operation.service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.service.MemberService;
import org.sscc.ssccopsserver.domain.operation.code.error.OperationErrorCode;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkCreateResponse;
import org.sscc.ssccopsserver.domain.operation.entity.OperationEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkChecklistItemEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkTypeEntity;
import org.sscc.ssccopsserver.domain.operation.entity.WorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.WorkStatus;
import org.sscc.ssccopsserver.domain.operation.repository.OperationRepository;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkChecklistItemRepository;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkRepository;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkTypeRepository;
import org.sscc.ssccopsserver.domain.operation.repository.WorkRepository;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SubWorkServiceImpl implements SubWorkService {

    private final OperationRepository operationRepository;
    private final WorkRepository workRepository;
    private final SubWorkRepository subWorkRepository;
    private final SubWorkTypeRepository subWorkTypeRepository;
    private final SubWorkChecklistItemRepository subWorkChecklistItemRepository;
    private final MemberService memberService;

    /*
     * oper(공통)·sub_work(확장)·체크리스트를 한 트랜잭션에서 INSERT 한다. 체크리스트 없이
     * 하위 업무만 남으면 완료 조건이 없는 업무가 되므로 경계를 쪼개지 않는다 (AR-11).
     */
    @Override
    @Transactional
    public SubWorkCreateResponse createSubWork(
            SubWorkCreateRequest request, MemberEntity registrant) {
        // 담당자 실재 여부는 회원 도메인 Service를 경유해 확인한다 (AR-07·LY-10)
        MemberEntity owner =
                memberService
                        .findAssignableMember(request.ownerId())
                        .orElseThrow(
                                () ->
                                        new GeneralException(
                                                OperationErrorCode.OWNER_NOT_ACTIVE_MEMBER));

        // 화면 안내대로 하위 업무는 상위 업무 안에서만 생긴다. 삭제된 상위 업무는 없는 것으로 본다
        WorkEntity parentWork =
                workRepository
                        .findByIdAndOperationDeletedAtIsNull(request.workId())
                        .orElseThrow(() -> new GeneralException(OperationErrorCode.WORK_NOT_FOUND));

        SubWorkTypeEntity subWorkType =
                subWorkTypeRepository
                        .findById(request.subWorkTypeId())
                        .orElseThrow(
                                () ->
                                        new GeneralException(
                                                OperationErrorCode.SUB_WORK_TYPE_NOT_FOUND));

        Instant beginAt = toInstant(request.startAt());
        Instant endAt = toInstant(request.endAt());
        // DTO의 @AssertTrue가 이미 걸러내지만, Service를 직접 호출하는 경로에서도 성립해야 하는 규칙이다
        validatePeriod(beginAt, endAt);

        OperationEntity operation =
                operationRepository.save(
                        OperationEntity.createForSubWork(
                                request.title(),
                                registrant,
                                owner,
                                beginAt,
                                endAt,
                                request.priority()));
        SubWorkEntity subWork =
                subWorkRepository.save(
                        SubWorkEntity.create(
                                parentWork,
                                operation,
                                subWorkType,
                                request.title(),
                                request.content(),
                                request.externalLink(),
                                toInstant(request.dueAt())));

        List<SubWorkChecklistItemEntity> checklist = createChecklist(subWork, subWorkType);
        recalculateParentProgressRate(parentWork);

        return SubWorkCreateResponse.of(subWork, checklist);
    }

    // 유형에 정의된 완료 점검 항목을 순서대로 복사한다 (REQ-021)
    private List<SubWorkChecklistItemEntity> createChecklist(
            SubWorkEntity subWork, SubWorkTypeEntity subWorkType) {
        List<String> articles = subWorkType.completionCheckArticles();
        List<SubWorkChecklistItemEntity> items = new ArrayList<>(articles.size());
        for (int index = 0; index < articles.size(); index++) {
            items.add(SubWorkChecklistItemEntity.create(subWork, articles.get(index), index + 1));
        }
        return subWorkChecklistItemRepository.saveAll(items);
    }

    /*
     * 상위 업무의 진행률은 하위 업무 완료율에서 나오므로 하위 업무가 하나 늘 때마다 다시 센다.
     * 방금 등록한 건은 기획 상태라 분모만 늘어 진행률이 내려간다 — 의도된 동작이다.
     */
    private void recalculateParentProgressRate(WorkEntity parentWork) {
        long total = subWorkRepository.countByWorkAndOperationDeletedAtIsNull(parentWork);
        long completed =
                subWorkRepository.countByWorkAndWorkStatusAndOperationDeletedAtIsNull(
                        parentWork, WorkStatus.DONE);
        parentWork.recalculateProgressRate(completed, total);
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
