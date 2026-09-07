package org.sscc.ssccopsserver.domain.event.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/*
 * 저장으로 본문에서 빠진 이미지를 골라내는 규칙 (ssccops#188).
 *
 * 통합 테스트로는 확인할 수 없다 — 삭제가 커밋 뒤에 일어나는데 통합 테스트는 @Transactional이라
 * 그 시점이 오지 않는다. 그래서 **무엇을 지울 것인가**를 값으로 확인한다.
 *
 * 여기서 틀리면 결과가 삭제라 되돌릴 수 없으므로, 단언의 무게는 "지우지 않아야 할 것을 지우지
 * 않는다" 쪽에 있다.
 */
class EventDroppedImageKeysTest {

    private static final long EVENT_ID = 3L;

    private final String kept = UUID.randomUUID() + ".png";
    private final String dropped = UUID.randomUUID() + ".webp";

    private String urlOf(String fileName) {
        return "https://api.example.com" + EventImageLocation.publicPathOf(EVENT_ID, fileName);
    }

    /** 남아 있는 것은 두고 빠진 것만 고른다 */
    @Test
    void picksOnlyTheOnesNoLongerReferenced() {
        assertThat(
                        EventServiceImpl.droppedImageKeys(
                                EVENT_ID, Set.of(kept, dropped), "![](" + urlOf(kept) + ")", null))
                .containsExactly("events/" + EVENT_ID + "/" + dropped);
    }

    /** 썸네일에만 남아 있어도 살아 있는 참조다 — 본문과 썸네일을 함께 본다 */
    @Test
    void countsThumbnailAsAReference() {
        assertThat(
                        EventServiceImpl.droppedImageKeys(
                                EVENT_ID, Set.of(kept), "본문에는 없다", urlOf(kept)))
                .isEmpty();
    }

    /** 본문이 그대로면 아무것도 지우지 않는다 — 제목만 고치는 저장이 이미지를 없애면 안 된다 */
    @Test
    void erasesNothingWhenBodyKeepsEveryImage() {
        String body = "![](" + urlOf(kept) + ") ![](" + urlOf(dropped) + ")";

        assertThat(EventServiceImpl.droppedImageKeys(EVENT_ID, Set.of(kept, dropped), body, null))
                .isEmpty();
    }

    /** 이전에 참조가 없었으면 볼 것도 없다 */
    @Test
    void erasesNothingWhenThereWasNoReferenceBefore() {
        assertThat(EventServiceImpl.droppedImageKeys(EVENT_ID, Set.of(), "아무 본문", null)).isEmpty();
    }

    /*
     * 본문을 통째로 비우면 있던 것이 전부 빠진 것이다. 되돌릴 수 없는 조작이므로 화면에서
     * 확인을 받는 것이 맞지만, 서버는 저장된 사실 그대로를 따른다.
     */
    @Test
    void erasesAllWhenBodyIsCleared() {
        assertThat(EventServiceImpl.droppedImageKeys(EVENT_ID, Set.of(kept, dropped), "", null))
                .containsExactlyInAnyOrder(
                        "events/" + EVENT_ID + "/" + kept, "events/" + EVENT_ID + "/" + dropped);
    }
}
