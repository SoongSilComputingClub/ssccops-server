package org.sscc.ssccopsserver.domain.content.dto;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;

import org.sscc.ssccopsserver.domain.content.code.error.ContentErrorCode;
import org.sscc.ssccopsserver.domain.content.entity.ContentPostEntity;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

/*
 * 익명 포스트 목록의 커서 (ssccops#381 · AP-13). 정렬이 «활동일 역순, 같은 날은 id 역순»이라
 * 두 값을 싣는다 — 활동일만 실으면 같은 날의 포스트가 경계에서 중복되거나 빠진다. 정렬 표기는
 * 싣지 않는다(순서가 하나뿐이다 — AcademicProgramApprovalCursor와 같은 판단).
 *
 * 형식은 Base64(url-safe · 패딩 없음)로 감싼 "yyyy-MM-dd:id"다. 익명 API라 값을 그대로 노출해도
 * 새는 것은 없지만, 클라이언트가 커서를 조립하지 않도록 불투명하게 둔다(다른 커서와 같다).
 */
public record ContentPostCursor(LocalDate activityDate, Long postId) {

    private static final String SEPARATOR = ":";

    public static ContentPostCursor of(ContentPostEntity lastRow) {
        return new ContentPostCursor(lastRow.getActivityDate(), lastRow.getId());
    }

    public String encode() {
        String plain = activityDate + SEPARATOR + postId;
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    public static ContentPostCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            String plain =
                    new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int separator = plain.lastIndexOf(SEPARATOR);
            if (separator < 0) {
                throw new IllegalArgumentException("구분자 없음");
            }
            return new ContentPostCursor(
                    LocalDate.parse(plain.substring(0, separator)),
                    Long.parseLong(plain.substring(separator + 1)));
        } catch (RuntimeException ex) {
            throw new GeneralException(ContentErrorCode.INVALID_CURSOR);
        }
    }
}
