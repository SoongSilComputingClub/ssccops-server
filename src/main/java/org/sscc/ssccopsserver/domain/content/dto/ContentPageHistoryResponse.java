package org.sscc.ssccopsserver.domain.content.dto;

import java.time.OffsetDateTime;

import org.sscc.ssccopsserver.domain.content.code.ContentPublishStatus;
import org.sscc.ssccopsserver.domain.content.entity.ContentPageHistoryEntity;

/** 페이지 개정 이력 한 행 — 그 시점의 제목·본문·상태 전체와 누가 언제 */
public record ContentPageHistoryResponse(
        Long pageHstryId,
        String ttl,
        String mtxt,
        ContentPublishStatus pubSttsCd,
        Long chgMbrId,
        String chgMbrNm,
        OffsetDateTime chgDt) {

    public static ContentPageHistoryResponse of(ContentPageHistoryEntity history) {
        return new ContentPageHistoryResponse(
                history.getId(),
                history.getTitle(),
                history.getBody(),
                history.getPublishStatus(),
                history.getChanger().getId(),
                history.getChanger().getName(),
                ContentPageResponse.toOffsetDateTime(history.getChangedAt()));
    }
}
