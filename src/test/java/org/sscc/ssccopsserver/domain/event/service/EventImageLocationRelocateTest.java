package org.sscc.ssccopsserver.domain.event.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/*
 * 행사 복제가 본문의 이미지 주소를 사본의 것으로 옮겨 적는 규칙 (ssccops#198 · 결정 2).
 *
 * 확인하려는 것은 "바꾼다"가 아니라 **무엇은 바꾸고 무엇은 남기는가**다 — 남의 행사 주소를
 * 바꾸면 그 오브젝트는 복사되지 않았으므로 사본에서 깨지고, 우리 형태가 아닌 파일명을 바꾸면
 * 원본에도 없던 주소가 생긴다.
 */
class EventImageLocationRelocateTest {

    private static final long SOURCE = 7L;
    private static final long COPY = 12L;
    private static final long OTHER = 3L;

    private final String first = UUID.randomUUID() + ".png";
    private final String second = UUID.randomUUID() + ".webp";

    private static String urlOf(long eventId, String fileName) {
        return "https://api.example.com" + EventImageLocation.publicPathOf(eventId, fileName);
    }

    /** 이 행사의 주소는 전부 사본 번호로 옮겨진다 — 호스트와 파일명은 그대로다 */
    @Test
    void movesEveryReferenceOfTheSourceEvent() {
        String body =
                "![포스터]("
                        + urlOf(SOURCE, first)
                        + ")\n\n<img src=\""
                        + urlOf(SOURCE, second)
                        + "\">";

        String relocated = EventImageLocation.relocateReferences(SOURCE, COPY, body);

        assertThat(relocated)
                .isEqualTo(
                        "![포스터]("
                                + urlOf(COPY, first)
                                + ")\n\n<img src=\""
                                + urlOf(COPY, second)
                                + "\">");
    }

    /** 남의 행사 주소는 건드리지 않는다 — 그 오브젝트는 복사되지 않았다 */
    @Test
    void leavesOtherEventsReferencesAlone() {
        String body = urlOf(SOURCE, first) + " " + urlOf(OTHER, second);

        assertThat(EventImageLocation.relocateReferences(SOURCE, COPY, body))
                .isEqualTo(urlOf(COPY, first) + " " + urlOf(OTHER, second));
    }

    /** 우리가 발급한 형태가 아닌 파일명은 이 행사의 주소라도 옮기지 않는다 — 우리 오브젝트가 아니다 */
    @Test
    void leavesForeignFileNamesAlone() {
        String foreign = "/public/v1/events/" + SOURCE + "/images/poster.png";

        assertThat(EventImageLocation.relocateReferences(SOURCE, COPY, foreign)).isEqualTo(foreign);
    }

    /** 번호가 앞부분만 겹치는 행사(7 vs 71)의 주소를 잘못 옮기지 않는다 */
    @Test
    void doesNotMatchEventIdByPrefix() {
        String body = urlOf(71L, first);

        assertThat(EventImageLocation.relocateReferences(SOURCE, COPY, body)).isEqualTo(body);
    }

    @Test
    void passesBlankTextThrough() {
        assertThat(EventImageLocation.relocateReferences(SOURCE, COPY, null)).isNull();
        assertThat(EventImageLocation.relocateReferences(SOURCE, COPY, "")).isEmpty();
    }
}
