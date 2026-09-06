package org.sscc.ssccopsserver.domain.event.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/*
 * 글에서 이 행사의 이미지 파일명을 긁어내는 규칙 (ssccops#188).
 *
 * 이 규칙이 지우는 대상을 정하므로 **틀리면 지워야 할 것을 못 지우거나 엉뚱한 것을 지운다.**
 * 후자가 훨씬 나쁘고 되돌릴 수 없어서, 아래 단언의 무게도 그쪽(찾지 않아야 할 것)에 있다.
 */
class EventImageLocationReferenceTest {

    private static final long EVENT_ID = 12L;

    private final String fileName = UUID.randomUUID() + ".png";

    /*
     * 마크다운 문법을 가리지 않는다 — 우리가 조립한 주소의 모양만 본다. 편집기가 이미지를
     * 어떤 문법으로 넣든(그리고 나중에 바꾸든) 이 규칙이 따라가지 않아도 되는 것이 요점이다.
     */
    @Test
    void findsFileNameRegardlessOfSurroundingMarkup() {
        String path = EventImageLocation.publicPathOf(EVENT_ID, fileName);

        for (String body :
                new String[] {
                    "![포스터](https://api.example.com" + path + ")",
                    "<img src=\"https://api.example.com" + path + "\">",
                    "그냥 주소만 https://api.example.com" + path + " 이렇게",
                    "호스트가 달라도 https://other.example.org" + path
                }) {
            assertThat(EventImageLocation.fileNamesReferencedIn(EVENT_ID, body))
                    .as(body)
                    .containsExactly(fileName);
        }
    }

    /*
     * **다른 행사의 주소는 찾지 않는다.** 운영자가 손으로 본문을 복사해 남의 행사 이미지가
     * 실려 있어도 그것은 이 행사의 소유가 아니라 지울 대상이 아니다 — 패턴에 행사 번호가
     * 박혀 있는 것이 그 방어선이다.
     */
    @Test
    void ignoresOtherEventsImages() {
        String otherEventPath = EventImageLocation.publicPathOf(EVENT_ID + 1, fileName);

        assertThat(
                        EventImageLocation.fileNamesReferencedIn(
                                EVENT_ID, "![](https://api.example.com" + otherEventPath + ")"))
                .isEmpty();
    }

    /*
     * 우리가 발급한 형태(소문자 UUID + 허용 확장자)가 아니면 찾지 않는다. 경로 모양만 맞춰
     * 아무 문자열이나 적어 두는 것으로 키가 만들어지면 그것이 곧 키 조작이고, 여기서는 그
     * 결과가 **삭제**라 발급 경로보다 더 조심할 자리다.
     */
    @Test
    void ignoresFileNamesWeNeverIssued() {
        for (String candidate : new String[] {"poster.png", "..png", UUID.randomUUID() + ".svg"}) {
            String body =
                    "https://api.example.com/public/v1/events/" + EVENT_ID + "/images/" + candidate;
            assertThat(EventImageLocation.fileNamesReferencedIn(EVENT_ID, body))
                    .as(candidate)
                    .isEmpty();
        }
    }

    /** 여러 글을 함께 본다(본문 + 썸네일). 같은 이미지가 두 곳에 있어도 한 번만 센다 */
    @Test
    void collectsAcrossTextsWithoutDuplicates() {
        String url =
                "https://api.example.com" + EventImageLocation.publicPathOf(EVENT_ID, fileName);
        String second = UUID.randomUUID() + ".webp";
        String secondUrl =
                "https://api.example.com" + EventImageLocation.publicPathOf(EVENT_ID, second);

        assertThat(
                        EventImageLocation.fileNamesReferencedIn(
                                EVENT_ID, "![](" + url + ") ![](" + secondUrl + ")", url))
                .containsExactlyInAnyOrder(fileName, second);
    }

    /** null·빈 글은 그냥 지나간다 — 썸네일이 비어 있는 것이 정상이다 */
    @Test
    void toleratesNullAndBlankTexts() {
        assertThat(EventImageLocation.fileNamesReferencedIn(EVENT_ID, null, "", "   ")).isEmpty();
    }
}
