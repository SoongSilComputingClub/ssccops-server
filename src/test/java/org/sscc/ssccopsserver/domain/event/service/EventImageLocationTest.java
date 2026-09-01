package org.sscc.ssccopsserver.domain.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.sscc.ssccopsserver.domain.file.code.ImageFileType;

/*
 * 행사 이미지의 키·주소 규칙 (#161 · #208).
 *
 * **이 클래스가 지키는 것은 오브젝트 키다.** 읽기 경로는 파일명을 요청에서 받고, 그 값이 키에
 * 그대로 들어가면 같은 버킷의 학술 출석 인증사진을 지목할 수 있다(ssccops#156). 그래서 통과
 * 조건이 "안전해 보이는가"가 아니라 **"우리가 발급한 형태인가"** 다 — 허용 목록이지 금지
 * 목록이 아니며, 아래의 거절 사례들은 그 규칙에서 따라 나오는 결과일 뿐이다.
 */
class EventImageLocationTest {

    @Test
    void issuedFileNameIsUuidWithCanonicalExtension() {
        String fileName = EventImageLocation.newFileName(ImageFileType.JPEG);

        // 확장자는 요청의 jpg/jpeg가 아니라 표가 정한 하나로 굳는다
        assertThat(fileName).endsWith(".jpg");
        assertThat(EventImageLocation.isValidFileName(fileName)).isTrue();
    }

    /* 발급이 만든 키를 읽기가 그대로 다시 조립한다 — 이 둘이 갈리면 발급한 주소가 빈다 */
    @Test
    void objectKeyAndPublicPathShareOneFileName() {
        String fileName = EventImageLocation.newFileName(ImageFileType.PNG);

        assertThat(EventImageLocation.objectKeyOf(7L, fileName)).isEqualTo("events/7/" + fileName);
        assertThat(EventImageLocation.publicPathOf(7L, fileName))
                .isEqualTo("/public/v1/events/7/images/" + fileName);
    }

    /*
     * 발급한 적 없는 형태는 전부 거절한다. `../`나 경로 구분자가 대표적이지만 그것만 막는
     * 것이 아니라, **UUID + 허용 확장자가 아닌 것이 전부** 거절이다.
     */
    @Test
    void anythingWeDidNotIssueIsRejected() {
        String uuid = UUID.randomUUID().toString();

        assertThat(EventImageLocation.isValidFileName(null)).isFalse();
        assertThat(EventImageLocation.isValidFileName("")).isFalse();
        // 키를 다른 접두사로 끌고 가려는 값
        assertThat(EventImageLocation.isValidFileName("../academic-programs/1/a.png")).isFalse();
        assertThat(EventImageLocation.isValidFileName(uuid + "/../a.png")).isFalse();
        assertThat(EventImageLocation.isValidFileName("..png")).isFalse();
        // 확장자가 허용 목록 밖 — SVG는 스크립트를 담을 수 있어 애초에 뺐다
        assertThat(EventImageLocation.isValidFileName(uuid + ".svg")).isFalse();
        assertThat(EventImageLocation.isValidFileName(uuid + ".html")).isFalse();
        // UUID가 아니거나 확장자가 없다
        assertThat(EventImageLocation.isValidFileName("poster.png")).isFalse();
        assertThat(EventImageLocation.isValidFileName(uuid)).isFalse();
        // 질의 문자열을 끼워 서명 파라미터를 흉내 내려는 값
        assertThat(EventImageLocation.isValidFileName(uuid + ".png?x=1")).isFalse();
        /*
         * 대문자는 정규화하지 않고 거절한다. 우리가 마크다운에 넣는 값은 언제나 소문자이고,
         * 관용을 두면 "무엇이 키가 되는가"가 흐려진다(대소문자만 다른 키는 R2에서 서로 다른
         * 오브젝트다).
         */
        assertThat(EventImageLocation.isValidFileName(uuid.toUpperCase() + ".png")).isFalse();
    }

    /*
     * 부르는 쪽이 검사를 건너뛰어도 키가 만들어지지 않는다. 검사를 통과하지 않은 값으로 키가
     * 조립되는 경로가 하나라도 생기면 그것이 곧 키 조작이므로, 조립하는 자리에서 다시 본다.
     */
    @Test
    void assemblingWithAnUnvalidatedFileNameThrows() {
        assertThatThrownBy(() -> EventImageLocation.objectKeyOf(1L, "../academic-programs/1/a.png"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EventImageLocation.publicPathOf(1L, "poster.png"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
