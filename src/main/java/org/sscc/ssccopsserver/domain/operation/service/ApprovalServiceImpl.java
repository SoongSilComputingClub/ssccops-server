package org.sscc.ssccopsserver.domain.operation.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.operation.dto.ApprovalInboxItemResponse;
import org.sscc.ssccopsserver.domain.operation.dto.ApprovalInboxResponse;
import org.sscc.ssccopsserver.domain.operation.dto.ApprovalInboxSearchCondition;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkCursor;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkSearchQuery;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkApprovalVoteEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.VoteChoice;
import org.sscc.ssccopsserver.domain.operation.entity.WorkStatus;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkAgreedVoteCount;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkApprovalVoteRepository;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkChecklistItemRepository;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkChecklistProgress;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkLatestRejection;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkRejectionRepository;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkRepository;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkReviewRequest;
import org.sscc.ssccopsserver.domain.operation.repository.SubWorkStatusHistoryRepository;
import org.sscc.ssccopsserver.global.apipayload.PageResponse;

import lombok.RequiredArgsConstructor;

/*
 * 승인함 (OPS-017 · #47).
 *
 * 목록 자체는 하위 업무 목록(OPS-008)의 커서 검색을 그대로 쓴다 — 필터가 '업무 상태 + 승인 상태'
 * 조합이라 이미 있는 조건으로 표현되고, 두 번째 조회 엔진을 만들면 정렬·커서 규칙이 갈린다.
 * 이 서비스가 더하는 것은 카드가 필요로 하는 파생 값(검토요청 일시·정족수 진행·체크리스트·내 표·
 * 직전 반려 사유·승인 권한)이며, 전부 목록 전체를 한 번에 집계해 붙인다 (DB-13 — 카드마다
 * 조회하면 N+1이다).
 *
 * 쿼리 수는 페이지 크기와 무관하게 9회다: 목록 1 + 건수 2(필터·전체) + 이력 1 + 체크리스트 1 +
 * 투표 집계 1 + 내 표 1 + 반려 1 + 보는 사람의 역할 1. 테스트가 이 숫자를 못 박는다 — 이전 주석은
 * 체크리스트 집계를 빠뜨린 6회였고, 세지 않은 쿼리는 늘어도 아무도 모른다 (#62).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ApprovalServiceImpl implements ApprovalService {

    private final SubWorkRepository subWorkRepository;
    private final SubWorkChecklistItemRepository subWorkChecklistItemRepository;
    private final SubWorkStatusHistoryRepository subWorkStatusHistoryRepository;
    private final SubWorkApprovalVoteRepository subWorkApprovalVoteRepository;
    private final SubWorkRejectionRepository subWorkRejectionRepository;
    private final ApprovalAuthorityPolicy approvalAuthorityPolicy;

    private final Clock clock;

    @Override
    public ApprovalInboxResponse searchApprovals(
            ApprovalInboxSearchCondition condition, MemberEntity viewer) {
        Instant now = clock.instant();
        SubWorkSearchQuery query = condition.toQuery(now);

        // 다음 페이지가 있는지 알기 위해 한 건 더 읽어 왔으므로, 남는 한 건은 응답에서 덜어낸다
        List<SubWorkEntity> fetched = subWorkRepository.search(query);
        boolean hasNext = fetched.size() > query.size();
        List<SubWorkEntity> rows = hasNext ? fetched.subList(0, query.size()) : fetched;

        List<ApprovalInboxItemResponse> approvals = toItems(rows, viewer);
        PageResponse page =
                new PageResponse(
                        query.size(),
                        query.sort().getParameter(),
                        hasNext
                                ? SubWorkCursor.of(query.sort(), rows.get(rows.size() - 1)).encode()
                                : null,
                        hasNext,
                        subWorkRepository.countMatching(query),
                        subWorkRepository.countByOperationDeletedAtIsNull());
        return new ApprovalInboxResponse(approvals, page);
    }

    /*
     * 카드에 실을 파생 값을 한꺼번에 모아 붙인다. 빈 목록이면 IN () 이 되는 쿼리를 태우지 않고
     * 곧장 빈 결과로 답한다.
     */
    private List<ApprovalInboxItemResponse> toItems(List<SubWorkEntity> rows, MemberEntity viewer) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> subWorkIds = rows.stream().map(SubWorkEntity::getId).toList();

        Map<Long, SubWorkReviewRequest> reviewRequests =
                subWorkStatusHistoryRepository
                        .findReviewRequestsBySubWorkIds(subWorkIds, WorkStatus.REVIEW)
                        .stream()
                        .collect(
                                Collectors.toMap(
                                        SubWorkReviewRequest::getSubWorkId, Function.identity()));
        Map<Long, SubWorkChecklistProgress> progresses =
                subWorkChecklistItemRepository.findProgressBySubWorkIds(subWorkIds).stream()
                        .collect(
                                Collectors.toMap(
                                        SubWorkChecklistProgress::getSubWorkId,
                                        Function.identity()));
        // 회차별로 나눠 담아 이번 회차의 값만 고른다 — 이전 회차의 찬성은 세지 않는다
        Map<Long, Map<Integer, Long>> agreedCounts =
                subWorkApprovalVoteRepository.findAgreedCountsBySubWorkIds(subWorkIds).stream()
                        .collect(
                                Collectors.groupingBy(
                                        SubWorkAgreedVoteCount::getSubWorkId,
                                        Collectors.toMap(
                                                SubWorkAgreedVoteCount::getApprovalSequence,
                                                SubWorkAgreedVoteCount::getAgreedCount)));
        Map<Long, SubWorkApprovalVoteEntity> myVotes =
                subWorkApprovalVoteRepository
                        .findBySubWorkIdInAndVoterId(subWorkIds, viewer.getId())
                        .stream()
                        .collect(
                                Collectors.toMap(
                                        vote -> vote.getSubWork().getId(),
                                        Function.identity(),
                                        // 회차가 여럿이면 마지막 회차의 표를 남긴다
                                        (older, newer) ->
                                                newer.getApprovalSequence()
                                                                >= older.getApprovalSequence()
                                                        ? newer
                                                        : older));
        Map<Long, SubWorkLatestRejection> latestRejections =
                subWorkRejectionRepository.findLatestRejectionsBySubWorkIds(subWorkIds).stream()
                        .collect(
                                Collectors.toMap(
                                        SubWorkLatestRejection::getSubWorkId,
                                        Function.identity(),
                                        /*
                                         * 같은 시각에 기록된 반려가 둘이면 식별자가 큰 쪽이
                                         * 최신이다 — 상세(OPS-009)가 쓰는 정렬
                                         * (반려 일시 desc, 식별자 desc)과 같은 기준이라야
                                         * 두 화면이 서로 다른 사유를 보여주지 않는다.
                                         */
                                        (older, newer) ->
                                                newer.getRejectionId() >= older.getRejectionId()
                                                        ? newer
                                                        : older));
        /*
         * 승인 권한은 보는 사람의 역할과 유형의 승인자 역할만으로 갈린다. 역할은 카드마다
         * 달라지지 않으므로 한 번만 읽어 재사용한다 (판정 규칙 자체는 정책 한 곳에 있다).
         */
        Predicate<SubWorkEntity> decidable = approvalAuthorityPolicy.decidableBy(viewer);

        return rows.stream()
                .map(
                        subWork ->
                                toItem(
                                        subWork,
                                        reviewRequests.get(subWork.getId()),
                                        progresses.get(subWork.getId()),
                                        agreedCounts.getOrDefault(subWork.getId(), Map.of()),
                                        myVotes.get(subWork.getId()),
                                        latestRejections.get(subWork.getId()),
                                        decidable.test(subWork)))
                .toList();
    }

    private ApprovalInboxItemResponse toItem(
            SubWorkEntity subWork,
            SubWorkReviewRequest reviewRequest,
            SubWorkChecklistProgress progress,
            Map<Integer, Long> agreedCountBySequence,
            SubWorkApprovalVoteEntity myVote,
            SubWorkLatestRejection latestRejection,
            boolean canDecide) {
        /*
         * 회차는 검토 진입 횟수다. 검토요청을 한 번도 하지 않은 건은 이력이 없어 0인데,
         * 대기 탭은 검토 상태만 담으므로 실제로는 승인·반려 탭에서만 그런 행이 나올 수 있다.
         */
        int approvalSequence = reviewRequest == null ? 0 : (int) reviewRequest.getReviewCount();
        Instant requestedAt = reviewRequest == null ? null : reviewRequest.getLastRequestedAt();
        long agreedCount = agreedCountBySequence.getOrDefault(approvalSequence, 0L);
        long completedItems = progress == null ? 0L : progress.getCompletedCount();
        long totalItems = progress == null ? 0L : progress.getTotalCount();

        // 이전 회차에 던진 표는 이번 회차의 선택 상태가 아니다
        VoteChoice choice =
                myVote != null && myVote.getApprovalSequence() == approvalSequence
                        ? myVote.choice()
                        : null;

        return ApprovalInboxItemResponse.of(
                subWork,
                requestedAt,
                agreedCount,
                completedItems,
                totalItems,
                choice,
                latestRejection == null ? null : latestRejection.getReason(),
                canDecide);
    }
}
