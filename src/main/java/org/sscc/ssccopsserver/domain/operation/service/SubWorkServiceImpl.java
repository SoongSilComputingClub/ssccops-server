package org.sscc.ssccopsserver.domain.operation.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.service.MemberService;
import org.sscc.ssccopsserver.domain.operation.code.error.OperationErrorCode;
import org.sscc.ssccopsserver.domain.operation.dto.ApprovalQuorumResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkChecklistItemUpdateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkChecklistItemUpdateResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkChecklistSummaryResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkCreateResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkCursor;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkDetailResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkRejectionResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkSearchCondition;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkSearchQuery;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkSearchResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkSummaryResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkTransitionRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkTransitionResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkUpdateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkVoteRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkVoteResponse;
import org.sscc.ssccopsserver.domain.operation.entity.ApprovalStatus;
import org.sscc.ssccopsserver.domain.operation.entity.OperationEntity;
import org.sscc.ssccopsserver.domain.operation.entity.OperationPriority;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkApprovalEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkApprovalVoteEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkChecklistItemEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkRejectionEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkStatusHistoryEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkTypeEntity;
import org.sscc.ssccopsserver.domain.operation.entity.TransitionAction;
import org.sscc.ssccopsserver.domain.operation.entity.VoteChoice;
import org.sscc.ssccopsserver.domain.operation.entity.WorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.WorkStatus;
import org.sscc.ssccopsserver.domain.operation.repository.OperationRepository;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkApprovalRepository;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkApprovalVoteRepository;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkChecklistItemRepository;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkChecklistProgress;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkRejectionRepository;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkRepository;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkStatusHistoryRepository;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkTypeRepository;
import org.sscc.ssccopsserver.domain.operation.repository.WorkRepository;
import org.sscc.ssccopsserver.global.apipayload.PageResponse;
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
    private final SubWorkStatusHistoryRepository subWorkStatusHistoryRepository;
    private final SubWorkApprovalRepository subWorkApprovalRepository;
    private final SubWorkApprovalVoteRepository subWorkApprovalVoteRepository;
    private final SubWorkRejectionRepository subWorkRejectionRepository;
    private final MemberService memberService;

    // 승인자·투표자 판정은 한 곳에만 둔다 (권한 인가 #9와 층이 다르다 — 그쪽 주석 참고)
    private final ApprovalAuthorityPolicy approvalAuthorityPolicy;

    // "담당자 본인인가"의 유일한 구현 (#101) — WORK_MANAGE가 없는 회원(국원)은 이 판정을 거친다
    private final SubWorkOwnershipPolicy subWorkOwnershipPolicy;

    // 지연 판정 경계(오늘 0시)를 만드는 유일한 자리 (#121). 상세·목록·대시보드가 같은 값을 쓴다
    private final DeadlinePolicy deadlinePolicy;

    // 상태 전이·투표 일시 등 '지금'이 필요한 자리. 테스트에서 고정할 수 있도록 주입받는다
    private final Clock clock;

    /*
     * 수정(updateSubWork)이 mdfcn_dt를 바로 응답에 실어야 해서 필요하다 — @LastModifiedDate는
     * flush 시점(JPA @PreUpdate 콜백)에야 in-memory 엔티티에 채워지므로, flush 없이 바로
     * SubWorkDetailResponse를 만들면 방금 바꾼 값인데도 updatedAt이 직전 값 그대로 나간다.
     */
    private final EntityManager entityManager;

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

        /*
         * 사용하지 않는 유형은 새로 고를 수 없다 (#43). 없는 유형(404)과 나누는 것은 유형이
         * 실재하기 때문이다 — 화면은 드롭다운을 useYn=true로 채우므로 여기 걸리는 것은
         * 목록을 받은 뒤 유형이 꺼진 경우이고, 그때 '없는 유형'이라고 답하면 오해를 부른다.
         * 이미 이 유형으로 등록된 하위 업무는 그대로 살아 있다.
         */
        if (!subWorkType.isActive()) {
            throw new GeneralException(OperationErrorCode.SUB_WORK_TYPE_INACTIVE);
        }

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

        return SubWorkCreateResponse.of(
                subWork, checklist, subWork.isDelayedBefore(deadlinePolicy.overdueBefore()));
    }

    /*
     * 상세 조회(OPS-009). 연관은 @EntityGraph가 한 번에 끌어오고 체크리스트만 따로 세므로
     * 하위 업무 1 + 체크리스트 1 + 최근 반려 1로 3회이며, 여기에 승인 관련 값이 붙는다 (#58):
     * 승인이 필요한 유형이면 역할 1회, 정족수 유형이면 회차·찬성 수·내 표 3회가 더해져 최대 7회다.
     * 정족수를 쓰지 않는 유형에는 투표 자체가 없으므로 그 세 쿼리를 태우지 않는다.
     *
     * 조회는 어떤 상태도 바꾸지 않는다 (AP-07) — 지연 여부도 컬럼을 갱신하지 않고 응답에서만 판정한다.
     */
    @Override
    public SubWorkDetailResponse getSubWork(Long subWorkId, MemberEntity viewer) {
        return buildDetail(findSubWork(subWorkId), viewer);
    }

    /*
     * 기본 정보 수정(OPS-030). oper(제목·기간·우선순위·담당자)와 sub_work(제목·업무 내용·
     * 완료 기준 내용·외부 링크·마감 일시)를 한 트랜잭션에서 함께 바꾼다 — 등록이 두 행을
     * 함께 만드는 것과 같은 경계다(AR-11).
     *
     * workId·subWorkTypeId·workStatus·approvalStatus는 손대지 않는다 — 요청 DTO에 그 필드가
     * 아예 없어(SubWorkUpdateRequest 주석) 여기서 막을 것도 없다.
     */
    @Override
    @Transactional
    public SubWorkDetailResponse updateSubWork(
            Long subWorkId, SubWorkUpdateRequest request, MemberEntity viewer) {
        SubWorkEntity subWork = findSubWork(subWorkId);
        // WORK_MANAGE가 없는 회원은 자신이 담당자인 건만 수정할 수 있다 (#101)
        subWorkOwnershipPolicy.requireOwnerOrManager(subWork, viewer);

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

        // sub_work_ttl은 oper_ttl과 값이 같아야 하므로 둘을 나란히 바꾼다 (SubWorkEntity 주석)
        OperationEntity operation = subWork.getOperation();
        operation.changeTitle(request.title());
        operation.changeSchedule(beginAt, endAt);
        // 등록 팩토리(createForSubWork)와 같은 기본값 — 생략하면 NORMAL이다
        operation.changePriority(orNormalPriority(request.priority()));
        operation.changePersonInCharge(owner);

        subWork.changeTitle(request.title());
        subWork.changeDueAt(toInstant(request.dueAt()));
        subWork.writeContent(request.content());
        subWork.writeCompletionCriteria(request.completionCriteria());
        subWork.changeExternalLink(request.externalLink());

        // mdfcn_dt를 지금 바꾼 값으로 응답에 실으려면 flush로 감사 필드를 먼저 채워야 한다
        entityManager.flush();

        return buildDetail(subWork, viewer);
    }

    private SubWorkEntity findSubWork(Long subWorkId) {
        return subWorkRepository
                .findByIdAndOperationDeletedAtIsNull(subWorkId)
                .orElseThrow(() -> new GeneralException(OperationErrorCode.SUB_WORK_NOT_FOUND));
    }

    private SubWorkDetailResponse buildDetail(SubWorkEntity subWork, MemberEntity viewer) {
        List<SubWorkChecklistItemEntity> checklist =
                subWorkChecklistItemRepository.findBySubWorkOrderBySortOrderAsc(subWork);

        /*
         * 회차는 정족수 유형에서만 센다 — 투표가 없는 유형에는 쓸 곳이 없다. 한 번만 세어
         * 정족수 진행과 내 표가 같은 회차를 보게 한다(따로 세면 그 사이의 전이로 갈릴 수 있다).
         */
        SubWorkTypeEntity subWorkType = subWork.getSubWorkType();
        int approvalSequence = subWorkType.requiresQuorum() ? currentApprovalSequence(subWork) : 0;

        return SubWorkDetailResponse.of(
                subWork,
                checklist,
                subWork.isDelayedBefore(deadlinePolicy.overdueBefore()),
                quorumOf(subWork, approvalSequence),
                myVoteOn(subWork, viewer, approvalSequence),
                SubWorkRejectionResponse.from(
                        subWorkRejectionRepository
                                .findFirstBySubWorkOrderByRejectedAtDescIdDesc(subWork)
                                .orElse(null)),
                canDecideOn(subWork, viewer));
    }

    /*
     * canApprove·canReject의 판정 (#101). transitionSubWork의 승인·반려 게이트와 정확히 같은
     * 갈림을 쓴다 — 승인 필요 유형은 승인자 판정(ApprovalAuthorityPolicy), 승인 필요 없는
     * 유형은 담당자·WORK_MANAGE 판정(SubWorkOwnershipPolicy)이다. 둘 중 하나만 바뀌면 버튼은
     * 보이는데 누르면 403이 나는 자리가 생긴다.
     */
    private boolean canDecideOn(SubWorkEntity subWork, MemberEntity viewer) {
        if (subWork.getSubWorkType().isApprovalNeeded()) {
            return approvalAuthorityPolicy.canDecide(subWork, viewer);
        }
        return subWorkOwnershipPolicy.isOwnerOrManager(subWork, viewer);
    }

    private OperationPriority orNormalPriority(OperationPriority priority) {
        return priority == null ? OperationPriority.NORMAL : priority;
    }

    /*
     * 상세의 정족수 진행 (#58). 승인함 카드(OPS-017)와 같은 값을 같은 규칙으로 만든다 —
     * 이번 회차의 찬성만 세고, 정족수 유형이 아니면 세지 않는다.
     */
    private ApprovalQuorumResponse quorumOf(SubWorkEntity subWork, int approvalSequence) {
        SubWorkTypeEntity subWorkType = subWork.getSubWorkType();
        if (!subWorkType.requiresQuorum()) {
            return ApprovalQuorumResponse.of(subWorkType, 0L);
        }
        return ApprovalQuorumResponse.of(
                subWorkType,
                subWorkApprovalVoteRepository.countBySubWorkAndApprovalSequenceAndAgreedIsTrue(
                        subWork, approvalSequence));
    }

    /*
     * 내가 이번 회차에 던진 표 (#58). 이전 회차의 표는 이번 계획에 대한 동의가 아니므로
     * 버튼 선택 상태로 그리지 않는다 — 승인함 카드가 회차로 거르는 것과 같은 규칙이다.
     */
    private VoteChoice myVoteOn(SubWorkEntity subWork, MemberEntity viewer, int approvalSequence) {
        if (viewer == null || !subWork.getSubWorkType().requiresQuorum()) {
            return null;
        }
        return subWorkApprovalVoteRepository
                .findBySubWorkAndApprovalSequenceAndVoter(subWork, approvalSequence, viewer)
                .map(SubWorkApprovalVoteEntity::choice)
                .orElse(null);
    }

    /*
     * 목록 조회(OPS-008). 화면의 필터 칩 하나가 이 호출 하나다.
     *
     * 쿼리는 네 번이다 — 목록 · 체크리스트 진행률 집계 · 필터 건수 · 전체 건수. 하위 업무가
     * 몇 건이든 이 수는 변하지 않는다 (DB-13). 진행률 집계는 이번 페이지에 실린 건에 대해서만
     * 돌리며, 목록이 비면 아예 부르지 않는다 — 빈 컬렉션을 IN에 넘기면 DB에 따라 문법 오류다.
     *
     * 지연·마감임박 판정 경계는 한 번만 읽어 목록·건수·응답의 isDelayed가 모두 같은 값을
     * 보게 한다. 그 값은 '지금'이 아니라 오늘 0시다 (DeadlinePolicy, #121).
     */
    @Override
    public SubWorkSearchResponse searchSubWorks(SubWorkSearchCondition condition) {
        Instant overdueBefore = deadlinePolicy.overdueBefore();
        SubWorkSearchQuery query = condition.toQuery(overdueBefore);

        // 다음 페이지가 있는지 알기 위해 한 건 더 읽어 왔으므로, 남는 한 건은 응답에서 덜어낸다
        List<SubWorkEntity> fetched = subWorkRepository.search(query);
        boolean hasNext = fetched.size() > query.size();
        List<SubWorkEntity> rows = hasNext ? fetched.subList(0, query.size()) : fetched;

        Map<Long, SubWorkChecklistProgress> progressBySubWorkId = checklistProgressOf(rows);
        List<SubWorkSummaryResponse> subWorks =
                rows.stream()
                        .map(subWork -> toSummary(subWork, progressBySubWorkId, overdueBefore))
                        .toList();

        PageResponse page =
                new PageResponse(
                        query.size(),
                        query.sort().getParameter(),
                        nextCursorOf(query, rows, hasNext),
                        hasNext,
                        subWorkRepository.countMatching(query),
                        subWorkRepository.countByOperationDeletedAtIsNull());
        return new SubWorkSearchResponse(subWorks, page);
    }

    // 다음 커서는 이번 페이지의 마지막 행을 가리킨다. 마지막 페이지면 커서가 없다
    private String nextCursorOf(
            SubWorkSearchQuery query, List<SubWorkEntity> rows, boolean hasNext) {
        return hasNext ? SubWorkCursor.of(query.sort(), rows.get(rows.size() - 1)).encode() : null;
    }

    /*
     * 이번 페이지에 실린 하위 업무들의 체크리스트 완료 개수. 행마다 세면 그대로 N+1이라
     * 한 번에 집계한다 (DB-13). 체크리스트가 없는 하위 업무는 결과에 나오지 않으므로
     * 여기서는 담기지 않고, 진행률을 만들 때 0/0으로 채운다.
     */
    private Map<Long, SubWorkChecklistProgress> checklistProgressOf(List<SubWorkEntity> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        List<Long> subWorkIds = rows.stream().map(SubWorkEntity::getId).toList();
        return subWorkChecklistItemRepository.findProgressBySubWorkIds(subWorkIds).stream()
                .collect(
                        Collectors.toMap(
                                SubWorkChecklistProgress::getSubWorkId, progress -> progress));
    }

    private SubWorkSummaryResponse toSummary(
            SubWorkEntity subWork,
            Map<Long, SubWorkChecklistProgress> progressBySubWorkId,
            Instant overdueBefore) {
        SubWorkChecklistProgress progress = progressBySubWorkId.get(subWork.getId());
        long completedItems = progress == null ? 0L : progress.getCompletedCount();
        long totalItems = progress == null ? 0L : progress.getTotalCount();
        return SubWorkSummaryResponse.of(
                subWork, completedItems, totalItems, subWork.isDelayedBefore(overdueBefore));
    }

    /*
     * 상태 전이(OPS-010). 전이 판단은 엔티티가 하고 여기서는 조회·기록·집계만 맡는다 (LY-09).
     *
     * 업무 상태 변경 · 상태 이력 · 승인/반려 기록 · 상위 진행률 재집계가 한 트랜잭션이다.
     * 이력 없이 상태만 바뀌는 결과를 허용하지 않기 위해 경계를 쪼개지 않는다 (AR-11).
     */
    @Override
    @Transactional
    public SubWorkTransitionResponse transitionSubWork(
            Long subWorkId, SubWorkTransitionRequest request, MemberEntity performer) {
        SubWorkEntity subWork =
                subWorkRepository
                        .findByIdAndOperationDeletedAtIsNull(subWorkId)
                        .orElseThrow(
                                () -> new GeneralException(OperationErrorCode.SUB_WORK_NOT_FOUND));

        TransitionAction action = request.transition();
        WorkStatus previousWorkStatus = subWork.getWorkStatus();
        ApprovalStatus previousApprovalStatus = subWork.getApprovalStatus();
        Instant occurredAt = clock.instant();

        /*
         * 승인·완료와 반려는 유형이 지정한 승인자만 할 수 있다 (TR-03·TR-04 수행 권한 · #47).
         * 승인자 자격은 부서별 국장(홍보국장 등)처럼 WORK_MANAGE도 담당자도 아닌 회원에게도
         * 열려 있으므로(ApprovalAuthorityPolicy 주석) 담당자 판정(#101)을 여기 겹쳐 걸지 않는다
         * — 승인자 판정이 그 자체로 완결된 별도 축이다.
         *
         * **승인이 필요 없는 유형**(aprv_need_yn = false)은 얘기가 다르다. 그 경우
         * ApprovalAuthorityPolicy.canDecide가 회원을 보지 않고 바로 통과시키는데, 그건 원래
         * "컨트롤러가 WORK_MANAGE로 이미 걸러 뒀다"는 전제 위에 짠 규칙이었다(#47 당시 주석).
         * 컨트롤러 게이트를 WORK_READ로 낮춘 뒤(#101)로는 그 전제가 깨져, 담당자도
         * WORK_MANAGE도 아닌 국원이 남의 승인불필요 유형 완료·반려까지 건드릴 수 있었다.
         * 그래서 승인이 필요 없는 유형에서는 승인자 판정 대신 담당자 판정으로 대체한다 —
         * "그 유형의 완료는 담당자의 몫이다"라는 원래 주석의 뜻을 실제로 강제한다.
         *
         * 착수·검토요청은 원래도 담당자의 몫이라 WORK_MANAGE가 없는 회원은 자신이 담당자인
         * 건만 시도할 수 있다(#101). 상태를 바꾸기 전에 먼저 끊어야 권한 없는 요청이 이력을
         * 남기지 않는다.
         */
        boolean approverGoverned =
                subWork.getSubWorkType().isApprovalNeeded()
                        && (action == TransitionAction.APPROVE_COMPLETE
                                || action == TransitionAction.REJECT);
        if (approverGoverned) {
            approvalAuthorityPolicy.requireApprover(subWork, performer);
        } else {
            subWorkOwnershipPolicy.requireOwnerOrManager(subWork, performer);
        }

        subWork.applyTransition(
                action,
                request.reason(),
                isCompletionCriteriaMet(subWork, action),
                agreedVoteCount(subWork, action),
                occurredAt);

        SubWorkStatusHistoryEntity history =
                subWorkStatusHistoryRepository.save(
                        SubWorkStatusHistoryEntity.record(
                                subWork,
                                previousWorkStatus,
                                subWork.getWorkStatus(),
                                performer,
                                request.reason(),
                                occurredAt));

        boolean selfApproval = false;
        if (action == TransitionAction.APPROVE_COMPLETE) {
            selfApproval = isRegisteredBy(subWork, performer);
            subWorkApprovalRepository.save(
                    SubWorkApprovalEntity.record(
                            subWork, performer, history, occurredAt, selfApproval));
        } else if (action == TransitionAction.REJECT) {
            subWorkRejectionRepository.save(
                    SubWorkRejectionEntity.record(
                            subWork, performer, history, request.reason(), occurredAt));
        }

        return SubWorkTransitionResponse.of(
                subWork,
                action,
                previousWorkStatus,
                previousApprovalStatus,
                selfApproval,
                occurredAt);
    }

    /*
     * 정족수 승인 투표 (OPS-015 · #47). 승인함 카드의 찬성·반대 버튼 하나가 이 호출 하나다.
     *
     * 업무 상태·승인 상태를 바꾸지 않으므로 sub_work_stts_hstry에 남기지 않는다 — 투표는
     * 전이가 아니다. 정족수를 채웠다는 사실(met)도 저장하지 않고 셀 때마다 다시 센다:
     * 저장하면 표가 바뀔 때마다 갱신할 주체가 하나 더 생기고 실제 표와 어긋날 수 있다.
     */
    @Override
    @Transactional
    public SubWorkVoteResponse voteOnSubWork(
            Long subWorkId, SubWorkVoteRequest request, MemberEntity voter) {
        SubWorkEntity subWork =
                subWorkRepository
                        .findByIdAndOperationDeletedAtIsNull(subWorkId)
                        .orElseThrow(
                                () -> new GeneralException(OperationErrorCode.SUB_WORK_NOT_FOUND));

        // 정족수 유형인지·검토 단계인지·승인 대기 중인지를 한 번에 본다
        subWork.requireVotable();
        // 사전에 운영진 권한을 가진 회원이면 누구나 던질 수 있다 — 승인자만의 권한이 아니다
        approvalAuthorityPolicy.requireStaff(voter);

        int approvalSequence = currentApprovalSequence(subWork);
        Instant votedAt = clock.instant();
        VoteChoice choice = request.vote();

        /*
         * 1인 1표. 이미 던진 표가 있으면 새로 만들지 않고 바꾼다 — 화면이 낙관적으로 버튼을
         * 눌러 두고 재전송하는 흐름이라 두 번째 호출을 409로 끊으면 버튼이 잠긴다.
         * 선조회만으로는 동시 요청을 막지 못하므로 uk_sub_work_aprv_vote가 함께 지킨다.
         */
        subWorkApprovalVoteRepository
                .findBySubWorkAndApprovalSequenceAndVoter(subWork, approvalSequence, voter)
                .ifPresentOrElse(
                        vote -> vote.changeVote(choice, votedAt),
                        () ->
                                subWorkApprovalVoteRepository.save(
                                        SubWorkApprovalVoteEntity.cast(
                                                subWork,
                                                voter,
                                                approvalSequence,
                                                choice,
                                                votedAt)));

        // 방금 던진 표까지 세야 하므로 flush 후에 집계한다
        subWorkApprovalVoteRepository.flush();
        long agreedCount =
                subWorkApprovalVoteRepository.countBySubWorkAndApprovalSequenceAndAgreedIsTrue(
                        subWork, approvalSequence);
        int requiredCount = subWork.getSubWorkType().getMinAgreeCount();

        return new SubWorkVoteResponse(
                subWork.getId(),
                choice,
                agreedCount >= requiredCount,
                agreedCount,
                requiredCount,
                approvalSequence);
    }

    /*
     * 완료 체크리스트 항목 체크·해제 (OPS-013). 상세 화면의 체크박스 하나가 이 호출 하나다.
     *
     * 상태 전이가 아니므로 sub_work_stts_hstry에 남기지 않고 업무 상태·승인 상태도 건드리지
     * 않는다. 상위 업무 진행률(work_prgrs_rt)도 재집계하지 않는다 — 하위 업무 완료 건수에서
     * 나오는 값이라 체크로 변하지 않는다. 상위 업무 상세(OPS-003)가 보여주는 하위 업무별
     * 진행률은 저장 컬럼 없이 체크리스트에서 파생하므로(AGG-02) 다음 조회에 그대로 반영된다.
     *
     * WORK_MANAGE가 없는 회원은 자신이 담당자인 건의 체크리스트만 토글할 수 있다 (#101).
     * 체크 이력은 감사 로그(#8)가 붙을 때 performer가 한 번 더 쓰인다 — 그때 시그니처가
     * 바뀌지 않도록 지금부터 받아 둔다 (LY-05).
     */
    @Override
    @Transactional
    public SubWorkChecklistItemUpdateResponse updateChecklistItem(
            Long subWorkId,
            Long checklistItemId,
            SubWorkChecklistItemUpdateRequest request,
            MemberEntity performer) {
        SubWorkEntity subWork =
                subWorkRepository
                        .findByIdAndOperationDeletedAtIsNull(subWorkId)
                        .orElseThrow(
                                () -> new GeneralException(OperationErrorCode.SUB_WORK_NOT_FOUND));
        subWorkOwnershipPolicy.requireOwnerOrManager(subWork, performer);
        // 완료된 건은 체크를 되돌릴 수 없다. 항목을 찾기 전에 막아 상태를 먼저 알린다
        subWork.requireChecklistEditable();

        SubWorkChecklistItemEntity item =
                subWorkChecklistItemRepository
                        .findByIdAndSubWork(checklistItemId, subWork)
                        .orElseThrow(
                                () ->
                                        new GeneralException(
                                                OperationErrorCode.CHECKLIST_ITEM_NOT_FOUND));
        item.updateCompletion(request.isCompleted());

        return SubWorkChecklistItemUpdateResponse.of(subWorkId, item, checklistSummaryOf(subWork));
    }

    // 운영 대시보드(OPS-038) '다가오는 마감'의 폭. 조회 시점 기준 ±5일이다(이슈#60)
    private static final Duration UPCOMING_DEADLINE_WINDOW = Duration.ofDays(5);

    /*
     * 대시보드 '내 업무 목록' (OPS-038). owner가 담당자인 하위 업무 전량이며, 개인 스코프라
     * 목록 조회(OPS-008)의 커서 페이징을 쓰지 않는다. 진행률·지연 여부는 목록 조회와 같은
     * 방식으로 한 번에 집계한다(DB-13).
     */
    @Override
    public List<SubWorkSummaryResponse> findMyTasks(MemberEntity owner) {
        Instant overdueBefore = deadlinePolicy.overdueBefore();
        List<SubWorkEntity> rows = subWorkRepository.findAllByOwnerId(owner.getId());
        Map<Long, SubWorkChecklistProgress> progressBySubWorkId = checklistProgressOf(rows);
        return rows.stream()
                .map(subWork -> toSummary(subWork, progressBySubWorkId, overdueBefore))
                .toList();
    }

    /*
     * 대시보드 '다가오는 마감' (OPS-038). 조회 시점 기준 ±5일 범위에 마감이 있고 아직
     * 완료되지 않은 하위 업무다(이슈#60). ownerId가 있으면 그 담당자의 것만 본다(#101).
     *
     * 여기서만 '지금'과 지연 경계를 둘 다 쓴다 — 범위는 명세대로 조회 시점 기준 ±5일이고,
     * 각 행의 isDelayed는 목록·상세와 같은 날짜 단위 판정이다 (#121).
     */
    @Override
    public List<SubWorkSummaryResponse> findUpcomingDeadlines(Long ownerId) {
        Instant now = clock.instant();
        Instant from = now.minus(UPCOMING_DEADLINE_WINDOW);
        Instant to = now.plus(UPCOMING_DEADLINE_WINDOW);
        List<SubWorkEntity> rows =
                ownerId == null
                        ? subWorkRepository.findAllDueBetweenExcludingStatus(
                                from, to, WorkStatus.DONE)
                        : subWorkRepository.findAllDueBetweenExcludingStatusAndOwnerId(
                                from, to, WorkStatus.DONE, ownerId);
        Map<Long, SubWorkChecklistProgress> progressBySubWorkId = checklistProgressOf(rows);
        Instant overdueBefore = deadlinePolicy.overdueBefore();
        return rows.stream()
                .map(subWork -> toSummary(subWork, progressBySubWorkId, overdueBefore))
                .toList();
    }

    /*
     * 운영 통합(OPS-001)의 하위 업무 전량. 진행률·지연 판정은 대시보드·목록 조회와 같은
     * 집계·판정을 그대로 쓴다 — 스코프만 전체로 넓힌 것이라 새 규칙이 없다.
     * 쿼리는 목록 1 + 체크리스트 집계 1로 2회다 (DB-13).
     */
    @Override
    public List<SubWorkSummaryResponse> listSubWorks() {
        Instant overdueBefore = deadlinePolicy.overdueBefore();
        List<SubWorkEntity> rows = subWorkRepository.findAllAlive();
        Map<Long, SubWorkChecklistProgress> progressBySubWorkId = checklistProgressOf(rows);
        return rows.stream()
                .map(subWork -> toSummary(subWork, progressBySubWorkId, overdueBefore))
                .toList();
    }

    /*
     * 회원 상태 변경(#78)의 경고용 건수. 완료된 건과 삭제된 건은 빠진다(SubWorkRepository 주석).
     *
     * 식별자가 없으면 0이다 — 부르는 쪽(회원 도메인)이 회원을 손에 쥔 채 호출하므로 실제로는
     * 일어나지 않지만, null을 그대로 흘려보내면 조건이 조용히 아무것도 세지 않는 쪽으로 무너진다.
     */
    @Override
    public long countOngoingByOwner(Long ownerId) {
        if (ownerId == null) {
            return 0L;
        }
        return subWorkRepository.countByOwnerIdExcludingStatus(ownerId, WorkStatus.DONE);
    }

    /*
     * 갱신 후 '2/4 완료' 표기값. 항목 전체를 다시 로딩하지 않고 등록·상위 상세가 쓰는 집계
     * 쿼리를 단건으로 재사용한다. 같은 트랜잭션이라 JPQL 실행 전 flush가 일어나 방금 바꾼
     * 값이 반영된다.
     *
     * 방금 항목을 갱신한 하위 업무이므로 결과가 비는 경우는 없지만, 이 메서드가 다른 곳에서
     * 불릴 때를 대비해 체크리스트가 없는 경우를 0/0으로 둔다.
     */
    private SubWorkChecklistSummaryResponse checklistSummaryOf(SubWorkEntity subWork) {
        return subWorkChecklistItemRepository
                .findProgressBySubWorkIds(List.of(subWork.getId()))
                .stream()
                .findFirst()
                .map(
                        progress ->
                                new SubWorkChecklistSummaryResponse(
                                        progress.getCompletedCount(), progress.getTotalCount()))
                .orElseGet(() -> new SubWorkChecklistSummaryResponse(0, 0));
    }

    /*
     * 완료 체크리스트 충족 여부. 완료 승인이 아닌 전이는 이 조건을 보지 않으므로 세지 않는다 —
     * 착수·검토요청·반려마다 쿼리를 한 번 더 쓸 이유가 없다.
     */
    private boolean isCompletionCriteriaMet(SubWorkEntity subWork, TransitionAction action) {
        return action != TransitionAction.APPROVE_COMPLETE
                || subWorkChecklistItemRepository.countBySubWorkAndCompletedFalse(subWork) == 0;
    }

    /*
     * 최종 승인의 정족수 판정에 넘길 이번 회차 찬성 수 (#47). 승인·완료가 아닌 전이와
     * 정족수를 쓰지 않는 유형에서는 세지 않는다 — 쓰이지 않을 값에 쿼리를 태우지 않는다.
     */
    private long agreedVoteCount(SubWorkEntity subWork, TransitionAction action) {
        if (action != TransitionAction.APPROVE_COMPLETE
                || !subWork.getSubWorkType().requiresQuorum()) {
            return 0L;
        }
        return subWorkApprovalVoteRepository.countBySubWorkAndApprovalSequenceAndAgreedIsTrue(
                subWork, currentApprovalSequence(subWork));
    }

    /*
     * 지금이 몇 번째 승인 절차인지. 검토(REVIEW)에 들어간 횟수를 센다 — 반려되면 진행으로
     * 돌아갔다가 다시 올라오므로, 이 값이 올라간다는 것은 계획이 한 번 바뀌었다는 뜻이다.
     * 이전 회차의 찬성은 새 계획에 대한 동의가 아니므로 집계에서 빠진다.
     */
    private int currentApprovalSequence(SubWorkEntity subWork) {
        return (int)
                subWorkStatusHistoryRepository.countBySubWorkAndNextWorkStatus(
                        subWork, WorkStatus.REVIEW);
    }

    /*
     * 자가 승인 판정. 담당자가 아니라 등록자(oper.oper_rgtr_id) 기준이다 — 남을 담당자로
     * 지정해 등록할 수 있으므로 '스스로 올린 건을 스스로 승인했는지'는 등록자로만 알 수 있다.
     * 등록자를 알 수 없는 이관 데이터는 자가 승인이 아닌 것으로 본다.
     */
    private boolean isRegisteredBy(SubWorkEntity subWork, MemberEntity performer) {
        MemberEntity registrant = subWork.getOperation().getRegistrant();
        return registrant != null
                && performer != null
                && registrant.getId().equals(performer.getId());
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

    private void validatePeriod(Instant beginAt, Instant endAt) {
        if (beginAt != null && endAt != null && endAt.isBefore(beginAt)) {
            throw new GeneralException(OperationErrorCode.INVALID_OPERATION_PERIOD);
        }
    }

    private Instant toInstant(OffsetDateTime dateTime) {
        return dateTime == null ? null : dateTime.toInstant();
    }
}
