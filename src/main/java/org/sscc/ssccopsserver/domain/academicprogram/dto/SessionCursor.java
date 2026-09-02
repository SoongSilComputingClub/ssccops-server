package org.sscc.ssccopsserver.domain.academicprogram.dto;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;

import org.sscc.ssccopsserver.domain.academicprogram.code.error.AcademicProgramErrorCode;
import org.sscc.ssccopsserver.domain.academicprogram.entity.SessionEntity;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

/*
 * 회차 목록(#135)의 커서. 형식·규칙은 AcademicProgramCursor와 같다 — 마지막 행의 (정렬 키,
 * 식별자)를 Base64로 싣고, 정렬 표기를 함께 담아 해독할 때 대조한다.
 *
 * 정렬 값을 문자열로 싣고 비교 시점에 원래 타입으로 되돌리는 것은 두 정렬 키의 타입이 다르기
 * 때문이다(seqno=정수, actlYmd=날짜). 문자열 그대로 비교하면 날짜는 우연히 맞지만 정수는
 * 사전순이 되어 10회차가 2회차보다 앞에 온다.
 */
public record SessionCursor(SessionSortOrder sort, String sortValue, Long sessionId) {

    private static final String DELIMITER = "|";
    private static final int FIELD_COUNT = 3;

    public static SessionCursor of(SessionSortOrder sort, SessionEntity lastRow) {
        return new SessionCursor(sort, sort.sortValueOf(lastRow), lastRow.getId());
    }

    public String encode() {
        String plain =
                String.join(DELIMITER, sort.getParameter(), sortValue, String.valueOf(sessionId));
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    public static SessionCursor decode(String encoded, SessionSortOrder sort) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        String[] fields = decodeFields(encoded);
        if (fields.length != FIELD_COUNT || !fields[0].equals(sort.getParameter())) {
            throw new GeneralException(AcademicProgramErrorCode.INVALID_CURSOR);
        }
        try {
            SessionCursor cursor = new SessionCursor(sort, fields[1], Long.parseLong(fields[2]));
            // 정렬 키 타입으로 해석되지 않는 값은 여기서 걸러 낸다(질의 조립까지 끌고 가지 않는다)
            cursor.typedSortValue();
            return cursor;
        } catch (RuntimeException ex) {
            throw new GeneralException(AcademicProgramErrorCode.INVALID_CURSOR);
        }
    }

    /** 질의 파라미터로 바인딩할 값. 문자열이 아니라 정렬 키의 실제 타입으로 넘겨야 비교가 맞다 */
    public Object typedSortValue() {
        return sort.getKey() == SessionSortOrder.SortKey.REAL_DT
                ? LocalDate.parse(sortValue)
                : Integer.parseInt(sortValue);
    }

    private static String[] decodeFields(String encoded) {
        try {
            String plain =
                    new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            return plain.split("\\" + DELIMITER, -1);
        } catch (IllegalArgumentException ex) {
            throw new GeneralException(AcademicProgramErrorCode.INVALID_CURSOR);
        }
    }
}
