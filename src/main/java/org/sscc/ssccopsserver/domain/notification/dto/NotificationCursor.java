package org.sscc.ssccopsserver.domain.notification.dto;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.sscc.ssccopsserver.domain.notification.code.error.NotificationErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

/*
 * 알림 목록의 커서 — id 하나뿐이다 (id 내림차순 · AP-13). 콘텐츠 어드민 목록(ContentIdCursor)과
 * 같은 꼴이며, Base64는 암호가 아니라 표기다(클라이언트가 커서 내부에 기대지 않게).
 */
public record NotificationCursor(Long id) {

    public String encode() {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(String.valueOf(id).getBytes(StandardCharsets.UTF_8));
    }

    /** 값이 없으면 첫 페이지라 null. 깨진 커서는 400 — 첫 페이지로 조용히 되돌리지 않는다 */
    public static NotificationCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            String plain =
                    new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            return new NotificationCursor(Long.parseLong(plain));
        } catch (RuntimeException ex) {
            throw new GeneralException(NotificationErrorCode.INVALID_CURSOR);
        }
    }
}
