package org.sscc.ssccopsserver.domain.operation.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.operation.entity.ChecklistChangeType;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkChecklistHistoryEntity;

/*
 * 완료 체크리스트 변경 이력 한 건 (#307).
 *
 * 이력을 쌓기만 하고 볼 길을 두지 않으면 "이 업무는 왜 점검 항목이 셋뿐이었나"에 여전히
 * 답할 수 없다 — 조회하는 경로가 없으면 없는 것과 같다는 것이 ssccops#255가 소프트 삭제를
 * 이력의 대체로 인정하지 않은 이유이기도 하다. 그래서 기록과 함께 조회를 연다.
 *
 * changeType은 ADDED·MODIFIED·REMOVED다. 추가는 previousArticle이 NULL, 삭제는
 * nextArticle이 NULL이며, 지워진 항목의 마지막 문구는 previousArticle에 있다.
 * checklistItemId는 이미 없는 항목을 가리킬 수 있다(삭제가 하드다).
 *
 * 일시는 AP-12에 따라 Asia/Seoul 오프셋을 포함해 내려준다.
 */
public record SubWorkChecklistHistoryResponse(
        Long historyId,
        Long checklistItemId,
        ChecklistChangeType changeType,
        String previousArticle,
        String nextArticle,
        MemberSummaryResponse performer,
        OffsetDateTime changedAt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static SubWorkChecklistHistoryResponse from(SubWorkChecklistHistoryEntity history) {
        return new SubWorkChecklistHistoryResponse(
                history.getId(),
                history.getChecklistItemId(),
                history.getChangeType(),
                history.getPreviousArticle(),
                history.getNextArticle(),
                MemberSummaryResponse.from(history.getPerformer()),
                toOffsetDateTime(history.getChangedAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
