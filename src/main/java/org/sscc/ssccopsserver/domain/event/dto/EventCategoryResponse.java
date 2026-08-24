package org.sscc.ssccopsserver.domain.event.dto;

import org.sscc.ssccopsserver.domain.event.entity.EventClassificationEntity;

/*
 * 행사 분류 한 건 (ssccops#140 목록·생성·수정 응답).
 *
 * 필드명은 컬럼명(eventClsfCd·eventClsfNm·indctSeqno)을 그대로 따른다
 * (RoleClassificationResponse 선례).
 *
 * eventCount는 그 분류를 쓰는 행사 수이며 엔티티에 없는 값이라 팩토리가 따로 받는다.
 * 화면은 이 값으로 삭제 버튼을 잠근다 — 0이 아니면 삭제가 409 EVENT_CLASSIFICATION_IN_USE로
 * 거절된다. 분류마다 count 질의를 날리면 N+1이므로 집계는 서비스가 한 번에 해서 넘긴다 (DB-13).
 *
 * crtDt·mdfcnDt는 없다 — event_clsf에는 감사 컬럼 자체가 없다.
 */
public record EventCategoryResponse(
        String eventClsfCd, String eventClsfNm, Integer indctSeqno, long eventCount) {

    public static EventCategoryResponse of(
            EventClassificationEntity classification, long eventCount) {
        return new EventCategoryResponse(
                classification.getCode(),
                classification.getName(),
                classification.getDisplayOrder(),
                eventCount);
    }
}
