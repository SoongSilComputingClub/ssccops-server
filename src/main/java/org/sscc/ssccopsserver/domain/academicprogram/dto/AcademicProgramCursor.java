package org.sscc.ssccopsserver.domain.academicprogram.dto;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import org.sscc.ssccopsserver.domain.academicprogram.code.error.AcademicProgramErrorCode;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

/*
 * 목록 조회(#131)의 커서. 형식·규칙은 work 도메인의 WorkCursor와 같다(설계 결정 #3) — 마지막
 * 행의 (정렬 키, 식별자)를 Base64로 싣고, 정렬 표기를 함께 담아 해독할 때 대조한다.
 */
public record AcademicProgramCursor(
        AcademicProgramSortOrder sort, Instant sortValue, Long academicProgramId) {

    private static final String DELIMITER = "|";
    private static final int FIELD_COUNT = 3;
    private static final String NULL_KEY = "";

    public static AcademicProgramCursor of(
            AcademicProgramSortOrder sort, AcademicProgramEntity lastRow) {
        return new AcademicProgramCursor(sort, sort.sortValueOf(lastRow), lastRow.getId());
    }

    public String encode() {
        String plain =
                String.join(
                        DELIMITER,
                        sort.getParameter(),
                        sortValue == null ? NULL_KEY : sortValue.toString(),
                        String.valueOf(academicProgramId));
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    public static AcademicProgramCursor decode(String encoded, AcademicProgramSortOrder sort) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        String[] fields = decodeFields(encoded);
        if (fields.length != FIELD_COUNT || !fields[0].equals(sort.getParameter())) {
            throw new GeneralException(AcademicProgramErrorCode.INVALID_CURSOR);
        }
        try {
            Instant sortValue = fields[1].isEmpty() ? null : Instant.parse(fields[1]);
            return new AcademicProgramCursor(sort, sortValue, Long.parseLong(fields[2]));
        } catch (RuntimeException ex) {
            throw new GeneralException(AcademicProgramErrorCode.INVALID_CURSOR);
        }
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
