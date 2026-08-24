package org.sscc.ssccopsserver.domain.event.code;

import java.util.EnumSet;
import java.util.Set;

/*
 * 행사 게시 상태 전이 액션 (ssccops#139 · POST /v1/events/{eventId}/status).
 *
 * 클라이언트가 보내는 것은 "어떤 상태로 바꿔라"가 아니라 "무엇을 하겠다"다 — 다음 상태는
 * 이 표가 정한다 (FormStatusAction 선례).
 *
 *   현재 \ 액션 | PUBLISH | RETRACT | ARCHIVE | REPUBLISH
 *   DRAFT       | → PUBLISHED | ✕   | ✕       | ✕
 *   PUBLISHED   | ✕       | → DRAFT | → ARCHIVED | ✕
 *   ARCHIVED    | ✕       | ✕       | ✕       | → PUBLISHED
 *
 * DRAFT↔PUBLISHED·PUBLISHED→ARCHIVED·ARCHIVED→PUBLISHED만 허용한다. RETRACT(게시 철회)를
 * 두는 것은 잘못 게시한 운영자가 되돌릴 방법이 있어야 하기 때문이고, ARCHIVED→DRAFT를 열지
 * 않는 것은 보관에서 꺼내는 길이 재공개(REPUBLISH) 하나면 충분해서다 — 내용을 고치려면
 * 재공개 후 철회하면 된다. 기준 코드 밖의 값은 enum 역직렬화 단계에서 걸러져
 * INVALID_CODE_VALUE(400)가 된다.
 */
public enum EventStatusAction {

    /** 게시 — 작성 중인 행사를 공개 앱에 노출한다 */
    PUBLISH(EventStatus.PUBLISHED, EnumSet.of(EventStatus.DRAFT)),

    /** 게시 철회 — 게시된 행사를 작성 중으로 되돌린다 */
    RETRACT(EventStatus.DRAFT, EnumSet.of(EventStatus.PUBLISHED)),

    /** 보관 — 게시된 행사를 공개 목록에서 내린다 */
    ARCHIVE(EventStatus.ARCHIVED, EnumSet.of(EventStatus.PUBLISHED)),

    /** 재공개 — 보관된 행사를 다시 게시한다 */
    REPUBLISH(EventStatus.PUBLISHED, EnumSet.of(EventStatus.ARCHIVED));

    private final EventStatus targetStatus;
    private final Set<EventStatus> allowedFromStatuses;

    EventStatusAction(EventStatus targetStatus, Set<EventStatus> allowedFromStatuses) {
        this.targetStatus = targetStatus;
        this.allowedFromStatuses = allowedFromStatuses;
    }

    /** 이 액션이 현재 상태에서 허용되는가. 판단만 하고 오류는 던지지 않는다 — 던지는 자리는 EventEntity다 */
    public boolean isAllowedFrom(EventStatus currentStatus) {
        return allowedFromStatuses.contains(currentStatus);
    }

    public EventStatus targetStatus() {
        return targetStatus;
    }
}
