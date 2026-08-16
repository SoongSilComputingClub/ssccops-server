package org.sscc.ssccopsserver.domain.member.dto;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.sscc.ssccopsserver.domain.member.code.error.MemberErrorCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

/*
 * 회원 목록 조회의 커서 (AP-13, #76). 규칙은 업무 목록(WorkCursor)과 같다 — 마지막으로
 * 내려준 행의 (정렬 키, 식별자)를 Base64로 싣고, 정렬 표기를 함께 담아 해독할 때 대조한다.
 *
 * 정렬 키 하나로는 부족하다. 동명이인이나 같은 기수가 여럿이면 경계에서 항목이 잘리거나
 * 중복되므로 식별자로 동률을 끊는다.
 *
 * 필드 순서가 WorkCursor와 다르다(정렬·식별자·정렬 키). 회원 이름이 정렬 키라서 구분자가
 * 값 안에 들어올 여지가 있는데, 가변 길이 필드를 맨 뒤에 두고 개수를 제한해 쪼개면
 * 그 경우에도 앞의 두 필드가 흔들리지 않는다.
 *
 * Base64는 암호가 아니라 표기다. 클라이언트가 커서 내부에 기대는 것을 막아 서버가 나중에
 * 형식을 바꿀 수 있게 하려는 것이다.
 */
public record MemberCursor(MemberSortOrder sort, Long memberId, Object sortValue) {

    private static final String DELIMITER = "|";
    private static final int FIELD_COUNT = 3;

    public static MemberCursor of(MemberSortOrder sort, MemberEntity lastRow) {
        return new MemberCursor(sort, lastRow.getId(), sort.sortValueOf(lastRow));
    }

    public String encode() {
        String plain =
                String.join(
                        DELIMITER,
                        sort.getParameter(),
                        String.valueOf(memberId),
                        String.valueOf(sortValue));
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
    public static MemberCursor decode(String encoded, MemberSortOrder sort) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        String[] fields = decodeFields(encoded);
        if (fields.length != FIELD_COUNT || !fields[0].equals(sort.getParameter())) {
            throw new GeneralException(MemberErrorCode.INVALID_CURSOR);
        }
        try {
            return new MemberCursor(
                    sort, Long.parseLong(fields[1]), sort.parseSortValue(fields[2]));
        } catch (RuntimeException ex) {
            throw new GeneralException(MemberErrorCode.INVALID_CURSOR);
        }
    }

    private static String[] decodeFields(String encoded) {
        try {
            String plain =
                    new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            // 정렬 키 값에 구분자가 들어 있어도 앞의 두 필드는 흔들리지 않게 개수를 제한한다
            return plain.split("\\" + DELIMITER, FIELD_COUNT);
        } catch (IllegalArgumentException ex) {
            throw new GeneralException(MemberErrorCode.INVALID_CURSOR);
        }
    }
}
