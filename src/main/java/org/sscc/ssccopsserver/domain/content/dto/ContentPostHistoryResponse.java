package org.sscc.ssccopsserver.domain.content.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import org.sscc.ssccopsserver.domain.content.code.ContentCategory;
import org.sscc.ssccopsserver.domain.content.code.ContentPublishStatus;
import org.sscc.ssccopsserver.domain.content.entity.ContentPostHistoryEntity;

public record ContentPostHistoryResponse(
        Long postHstryId,
        ContentCategory cntntClsfCd,
        String ttl,
        String smry,
        String mtxt,
        LocalDate actvYmd,
        ContentPublishStatus pubSttsCd,
        Long chgMbrId,
        String chgMbrNm,
        OffsetDateTime chgDt) {

    public static ContentPostHistoryResponse of(ContentPostHistoryEntity history) {
        return new ContentPostHistoryResponse(
                history.getId(),
                history.getCategory(),
                history.getTitle(),
                history.getSummary(),
                history.getBody(),
                history.getActivityDate(),
                history.getPublishStatus(),
                history.getChanger().getId(),
                history.getChanger().getName(),
                ContentPageResponse.toOffsetDateTime(history.getChangedAt()));
    }
}
