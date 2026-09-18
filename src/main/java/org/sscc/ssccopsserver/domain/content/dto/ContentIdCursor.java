package org.sscc.ssccopsserver.domain.content.dto;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.sscc.ssccopsserver.domain.content.code.error.ContentErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

/** 어드민 목록(페이지·포스트)의 커서 — id 하나뿐이다 (id 내림차순 정렬) */
public record ContentIdCursor(Long id) {

    public String encode() {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(String.valueOf(id).getBytes(StandardCharsets.UTF_8));
    }

    public static ContentIdCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            String plain =
                    new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            return new ContentIdCursor(Long.parseLong(plain));
        } catch (RuntimeException ex) {
            throw new GeneralException(ContentErrorCode.INVALID_CURSOR);
        }
    }
}
