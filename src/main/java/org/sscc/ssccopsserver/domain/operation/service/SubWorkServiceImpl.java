package org.sscc.ssccopsserver.domain.operation.service;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.service.MemberService;
import org.sscc.ssccopsserver.domain.operation.code.error.OperationErrorCode;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkChecklistItemUpdateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkChecklistItemUpdateResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkChecklistSummaryResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkCreateResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkCursor;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkDetailResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkSearchCondition;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkSearchQuery;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkSearchResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkSummaryResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkTransitionRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkTransitionResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkVoteRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkVoteResponse;
import org.sscc.ssccopsserver.domain.operation.entity.ApprovalStatus;
import org.sscc.ssccopsserver.domain.operation.entity.OperationEntity;
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

    // 승인자·투표자 판정은 한 곳에만 둔다 — 역할 인가(#9)가 붙으면 통째로 옮겨간다
    private final ApprovalAuthorityPolicy approvalAuthorityPolicy;

    // 마감 경과 판정 기준 시각. 테스트에서 고정할 수 있도록 주입받는다 (ClockConfig)
    private final Clock clock;

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
        recalculateParentProgressRate(parentWork);

        return SubWorkCreateResponse.of(subWork, checklist);
    }

    /*
     * 상세 조회(OPS-009). 연관은 @EntityGraph가 한 번에 끌어오고 체크리스트만 따로 세므로
     * 쿼리는 2회다. 조회는 어떤 상태도 바꾸지 않는다 (AP-07) — 지연 여부도 컬럼을 갱신하지
     * 않고 응답에서만 판정한다.
     */
    @Override
    public SubWorkDetailResponse getSubWork(Long subWorkId) {
        SubWorkEntity subWork =
                subWorkRepository
                        .findByIdAndOperationDeletedAtIsNull(subWorkId)
                        .orElseThrow(
                                () -> new GeneralException(OperationErrorCode.SUB_WORK_NOT_FOUND));

        List<SubWorkChecklistItemEntity> checklist =
                subWorkChecklistItemRepository.findBySubWorkOrderBySortOrderAsc(subWork);

        return SubWorkDetailResponse.of(subWork, checklist, subWork.isDelayedAt(clock.instant()));
    }

    /*
     * 목록 조회(OPS-008). 화면의 필터 칩 하나가 이 호출 하나다.
     *
     * 쿼리는 네 번이다 — 목록 · 체크리스트 진행률 집계 · 필터 건수 · 전체 건수. 하위 업무가
     * 몇 건이든 이 수는 변하지 않는다 (DB-13). 진행률 집계는 이번 페이지에 실린 건에 대해서만
     * 돌리며, 목록이 비면 아예 부르지 않는다 — 빈 컬렉션을 IN에 넘기면 DB에 따라 문법 오류다.
     *
     * 지연·마감임박 판정 시각은 한 번만 읽어 목록과 건수가 같은 '지금'을 보게 한다.
     */
    @Override
    public SubWorkSearchResponse searchSubWorks(SubWorkSearchCondition condition) {
        Instant now = clock.instant();
        SubWorkSearchQuery query = condition.toQuery(now);

        // 다음 페이지가 있는지 알기 위해 한 건 더 읽어 왔으므로, 남는 한 건은 응답에서 덜어낸다
        List<SubWorkEntity> fetched = subWorkRepository.search(query);
        boolean hasNext = fetched.size() > query.size();
        List<SubWorkEntity> rows = hasNext ? fetched.subList(0, query.size()) : fetched;

        Map<Long, SubWorkChecklistProgress> progressBySubWorkId = checklistProgressOf(rows);
        List<SubWorkSummaryResponse> subWorks =
                rows.stream().map(subWork -> toSummary(subWork, progressBySubWorkId, now)).toList();

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
            Instant now) {
        SubWorkChecklistProgress progress = progressBySubWorkId.get(subWork.getId());
        long completedItems = progress == null ? 0L : progress.getCompletedCount();
        long totalItems = progress == null ? 0L : progress.getTotalCount();
        return SubWorkSummaryResponse.of(
                subWork, completedItems, totalItems, subWork.isDelayedAt(now));
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
         * 착수·검토요청은 담당자의 몫이라 검사하지 않는다 — 그쪽 통제는 역할 인가(#9)가 맡는다.
         * 상태를 바꾸기 전에 먼저 끊어야 권한 없는 요청이 이력을 남기지 않는다.
         */
        if (action == TransitionAction.APPROVE_COMPLETE || action == TransitionAction.REJECT) {
            approvalAuthorityPolicy.requireApprover(subWork, performer);
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
            // 완료 개수가 바뀌는 전이는 이것뿐이다. 완료에서 빠져나가는 전이는 전이표에 없다
            recalculateParentProgressRate(subWork.getWork());
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
     * performer는 아직 읽지 않는다. 권한 검사는 역할 인가(#9), 체크 이력은 감사 로그(#8)가
     * 붙을 때 이 자리에서 쓰인다 — 그때 시그니처가 바뀌지 않도록 지금부터 받아 둔다 (LY-05).
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
