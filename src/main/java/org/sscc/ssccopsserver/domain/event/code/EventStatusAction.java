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
 *   DRAFT       | → PUBLISHED | ✕   | → ARCHIVED | ✕
 *   PUBLISHED   | ✕       | → DRAFT | → ARCHIVED | ✕
 *   ARCHIVED    | ✕       | ✕       | ✕       | → PUBLISHED
 *
 * **DRAFT에서도 보관할 수 있다** (ssccops ADR-0014). 예전에는 게시된 행사만 보관할 수 있었고
 * 작성 중인 행사를 치우는 길은 삭제였는데, ADR-0014가 그 삭제를 걷어내면서 이 칸이 열렸다.
 * **삭제는 ADR-0020(#347)으로 소프트 삭제가 되어 돌아왔지만 이 칸은 닫지 않는다** — 보관과
 * 삭제는 뜻이 다르다. 보관은 끝난 행사를 공개에서 내리되 운영 기록으로 남기는 것이고, 삭제는
 * 잘못 만든 것을 목록에서 치우되 되돌릴 수 있게 두는 것이다. 작성 중인 행사를 "끝났다"고
 * 내리는 일은 여전히 있다(기획만 하고 열지 않은 행사).
 *
 * 삭제 여부(del_dt)는 이 표의 축이 아니다. DELETED를 상태로 넣지 않은 이유는
 * EventEntity.deletedAt 주석에 있다 — 되살릴 때 "어느 상태로 돌아가는가"를 적어 둘 자리가
 * 없어진다.
 *
 * DRAFT↔PUBLISHED·DRAFT→ARCHIVED·PUBLISHED→ARCHIVED·ARCHIVED→PUBLISHED를 허용한다. RETRACT(게시 철회)를
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

    /*
     * 보관 — 공개 목록에서 내린다. **작성 중(DRAFT)인 행사도 보관한다** (ssccops ADR-0014).
     * 잘못 만든 행사를 치우는 길은 이제 소프트 삭제(ADR-0020 · DELETE /v1/events/{eventId})이고,
     * 보관은 끝난 행사를 내리는 자리다 — 둘이 갈린 뒤에도 DRAFT 칸을 닫지 않는 이유는 위에 있다.
     */
    ARCHIVE(EventStatus.ARCHIVED, EnumSet.of(EventStatus.DRAFT, EventStatus.PUBLISHED)),

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
