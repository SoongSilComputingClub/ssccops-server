package org.sscc.ssccopsserver.domain.operation.dto;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import org.sscc.ssccopsserver.domain.operation.code.error.OperationErrorCode;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkEntity;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

/*
 * 목록 조회(OPS-008)의 커서 (AP-13). '마지막으로 내려준 행'을 가리키며, 다음 페이지는
 * 정렬 순서상 이 행보다 뒤에 있는 건들이다.
 *
 * 정렬 키 하나로는 부족하다 — 마감이 같은 하위 업무가 여럿이면 경계에서 항목이 잘리거나
 * 중복된다. 그래서 (정렬 키, 식별자) 두 값을 함께 싣고 식별자로 동률을 끊는다.
 *
 * 정렬 표기까지 함께 실어 두고 해독할 때 대조한다. 커서는 특정 정렬 기준 위에서만 의미가
 * 있어서, 1페이지를 마감순으로 받고 2페이지를 등록순으로 요청하면 결과가 조용히 어긋난다.
 *
 * Base64는 암호가 아니라 표기다. 클라이언트가 커서 내부를 들여다보고 규칙에 기대는 것을
 * 막아 서버가 나중에 형식을 바꿀 수 있게 하려는 것이다.
 */
public record SubWorkCursor(SubWorkSortOrder sort, Instant sortValue, Long subWorkId) {

    private static final String DELIMITER = "|";
    private static final int FIELD_COUNT = 3;

    // 정렬 키가 NULL인 행(마감 없는 하위 업무)의 자리표시자
    private static final String NULL_KEY = "";

    public static SubWorkCursor of(SubWorkSortOrder sort, SubWorkEntity lastRow) {
        return new SubWorkCursor(sort, sort.sortValueOf(lastRow), lastRow.getId());
    }

    public String encode() {
        String plain =
                String.join(
                        DELIMITER,
                        sort.getParameter(),
                        sortValue == null ? NULL_KEY : sortValue.toString(),
                        String.valueOf(subWorkId));
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    /*
     * 커서를 해독한다. 값이 없으면 첫 페이지라 null을 돌려준다.
     *
     * 형식이 깨졌거나 요청한 정렬과 다른 커서는 VALIDATION_FAILED(400)로 막는다. 첫 페이지로
     * 조용히 되돌리면 클라이언트는 목록을 무한히 처음부터 다시 받으면서도 알아채지 못한다.
     */
    public static SubWorkCursor decode(String encoded, SubWorkSortOrder sort) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        String[] fields = decodeFields(encoded);
        if (fields.length != FIELD_COUNT || !fields[0].equals(sort.getParameter())) {
            throw new GeneralException(OperationErrorCode.INVALID_CURSOR);
        }
        try {
            Instant sortValue = fields[1].isEmpty() ? null : Instant.parse(fields[1]);
            return new SubWorkCursor(sort, sortValue, Long.parseLong(fields[2]));
        } catch (RuntimeException ex) {
            throw new GeneralException(OperationErrorCode.INVALID_CURSOR);
        }
    }

    private static String[] decodeFields(String encoded) {
        try {
            String plain =
                    new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            // 빈 필드도 자리를 지켜야 하므로 뒤쪽 빈 문자열을 버리지 않는다
            return plain.split("\\" + DELIMITER, -1);
        } catch (IllegalArgumentException ex) {
            throw new GeneralException(OperationErrorCode.INVALID_CURSOR);
        }
    }
}
