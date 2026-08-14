package org.sscc.ssccopsserver.domain.operation.dto;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import org.sscc.ssccopsserver.domain.operation.code.error.OperationErrorCode;
import org.sscc.ssccopsserver.domain.operation.entity.WorkEntity;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

/*
 * 상위 업무 목록 조회(OPS-020)의 커서 (AP-13). 형식과 규칙은 하위 업무 목록(SubWorkCursor)과
 * 같다 — 마지막으로 내려준 행의 (정렬 키, 식별자)를 Base64로 싣고, 정렬 표기를 함께 담아
 * 해독할 때 대조한다.
 *
 * 정렬 키 하나로는 부족하다. 등록 시각이나 시작 일시가 같은 업무가 여럿이면 경계에서 항목이
 * 잘리거나 중복되므로 식별자로 동률을 끊는다.
 *
 * Base64는 암호가 아니라 표기다. 클라이언트가 커서 내부에 기대는 것을 막아 서버가 나중에
 * 형식을 바꿀 수 있게 하려는 것이다.
 */
public record WorkCursor(WorkSortOrder sort, Instant sortValue, Long workId) {

    private static final String DELIMITER = "|";
    private static final int FIELD_COUNT = 3;

    // 정렬 키가 NULL인 행(시작 일시 없는 업무)의 자리표시자
    private static final String NULL_KEY = "";

    public static WorkCursor of(WorkSortOrder sort, WorkEntity lastRow) {
        return new WorkCursor(sort, sort.sortValueOf(lastRow), lastRow.getId());
    }

    public String encode() {
        String plain =
                String.join(
                        DELIMITER,
                        sort.getParameter(),
                        sortValue == null ? NULL_KEY : sortValue.toString(),
                        String.valueOf(workId));
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    /*
     * 커서를 해독한다. 값이 없으면 첫 페이지라 null을 돌려준다.
     *
     * 형식이 깨졌거나 요청한 정렬과 다른 커서는 400으로 막는다. 첫 페이지로 조용히 되돌리면
     * 클라이언트는 목록을 무한히 처음부터 다시 받으면서도 알아채지 못한다.
     */
    public static WorkCursor decode(String encoded, WorkSortOrder sort) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        String[] fields = decodeFields(encoded);
        if (fields.length != FIELD_COUNT || !fields[0].equals(sort.getParameter())) {
            throw new GeneralException(OperationErrorCode.INVALID_CURSOR);
        }
        try {
            Instant sortValue = fields[1].isEmpty() ? null : Instant.parse(fields[1]);
            return new WorkCursor(sort, sortValue, Long.parseLong(fields[2]));
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
