package org.sscc.ssccopsserver.domain.member.service;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.member.code.error.MemberErrorCode;
import org.sscc.ssccopsserver.domain.member.dto.MemberImportCsv;
import org.sscc.ssccopsserver.domain.member.dto.MemberImportCsvRow;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

/*
 * 이관용 CSV 파서 (#84). **CSV를 해석하는 유일한 자리다.**
 *
 * 웹에서 헤더를 따로 파싱하지 않는 것이 이 클래스가 존재하는 이유다 — 파서가 두 벌이면
 * 따옴표로 감싼 헤더("회장,프로젝트장")에서 해석이 갈려, 화면에서 매핑한 컬럼과 서버가 읽는
 * 컬럼이 어긋난다. 미리보기(preview)를 서버에 둔 것도 같은 이유다.
 *
 * 직접 split 하지 않고 Commons CSV를 쓴다. 따옴표 안의 쉼표·줄바꿈을 포함한 필드·이스케이프된
 * 따옴표("")는 손으로 짠 분해로는 반드시 어느 하나에서 깨진다.
 *
 * 헤더를 CSVFormat의 header 기능으로 읽지 않고 **첫 레코드를 그대로 헤더로 쓴다**. 같은 헤더가
 * 두 번 나오는 파일(엑셀에서 흔하다)을 header 모드는 예외로 거절하는데, 그건 파일이 잘못된 것이
 * 아니라 매핑에서 하나를 고르면 되는 일이다.
 */
@Component
public class MemberImportParser {

    // UTF-8 BOM. 엑셀이 붙여 내보내므로 첫 헤더가 '﻿이름'이 되어 매핑이 통째로 빗나간다
    private static final char BYTE_ORDER_MARK = '﻿';

    /*
     * 원본 바이트를 UTF-8로 읽어 헤더와 데이터 행으로 나눈다.
     *
     * 잘못된 인코딩은 물음표로 흘려보내지 않고 INVALID_CSV_FILE로 끊는다(REPORT가 아니라
     * CodingErrorAction.REPORT) — 깨진 글자로 검증을 통과시키면 '?????'라는 이름이 이관된다.
     */
    public MemberImportCsv parse(byte[] content) {
        String text = stripByteOrderMark(decodeUtf8(content));

        try (CSVParser parser = CSVParser.parse(new StringReader(text), CSVFormat.DEFAULT)) {
            Iterator<CSVRecord> records = parser.iterator();
            if (!records.hasNext()) {
                throw new GeneralException(MemberErrorCode.EMPTY_CSV_FILE);
            }

            LineCounter lineCounter = new LineCounter(text);
            List<String> headers = trimmedValues(records.next());

            List<MemberImportCsvRow> rows = new ArrayList<>();
            while (records.hasNext()) {
                CSVRecord record = records.next();
                if (isBlankRecord(record)) {
                    // 값이 하나도 없는 줄은 데이터가 아니다. 엑셀이 파일 끝에 남기는 ,,,,를 세면
                    // 마지막 한 행이 늘 "회원명 누락" 오류로 잡힌다
                    continue;
                }
                rows.add(
                        new MemberImportCsvRow(
                                lineCounter.lineNumberAt(record.getCharacterPosition()),
                                trimmedValues(record)));
            }

            if (rows.isEmpty()) {
                throw new GeneralException(MemberErrorCode.EMPTY_CSV_FILE);
            }
            return new MemberImportCsv(headers, rows);

        } catch (IOException
                | UncheckedIOException
                | IllegalStateException
                | IllegalArgumentException ex) {
            // 따옴표가 닫히지 않는 등 CSV로 성립하지 않는 내용. 크기·확장자와 같은 코드로 묶는다
            throw new GeneralException(MemberErrorCode.INVALID_CSV_FILE);
        }
    }

    /*
     * 값의 앞뒤 공백을 떼는 것은 파싱 단계에서 한다. "홍길동 , 20211234"처럼 구분자 뒤에 공백을
     * 둔 파일이 흔하고, 그 공백을 남기면 필수값 검사는 통과하는데 저장되는 이름에 공백이 붙는다.
     * 따옴표 안의 공백도 함께 떨어지지만, 이름 양끝의 공백이 의미를 갖는 경우는 없다.
     */
    private static List<String> trimmedValues(CSVRecord record) {
        List<String> values = new ArrayList<>(record.size());
        for (String value : record) {
            values.add(value == null ? "" : value.trim());
        }
        return values;
    }

    private static boolean isBlankRecord(CSVRecord record) {
        for (String value : record) {
            if (value != null && !value.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static String decodeUtf8(byte[] content) {
        CharsetDecoder decoder =
                StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer decoded = decoder.decode(ByteBuffer.wrap(content));
            return decoded.toString();
        } catch (CharacterCodingException ex) {
            throw new GeneralException(MemberErrorCode.INVALID_CSV_FILE);
        }
    }

    private static String stripByteOrderMark(String text) {
        return !text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK ? text.substring(1) : text;
    }

    /*
     * 레코드 시작 문자 위치를 원본의 줄 번호로 바꾼다.
     *
     * 레코드 순번을 그대로 쓰지 않는 이유는 두 가지다: 줄바꿈을 포함한 필드가 있으면 레코드 3번이
     * 7행일 수 있고, 중간의 빈 줄은 레코드로 세지 않지만 파일에는 존재한다. 운영자가 파일을 열어
     * 찾는 것은 언제나 줄 번호다.
     *
     * 위치는 레코드 순서대로 증가하므로 커서를 앞으로만 밀며 개행을 센다 — 행마다 문자열을 처음부터
     * 훑으면 5MB 파일에서 시간이 제곱으로 는다.
     */
    private static final class LineCounter {

        private final String text;
        private int cursor;
        private long line = 1;

        private LineCounter(String text) {
            this.text = text;
        }

        private long lineNumberAt(long characterPosition) {
            int target = (int) Math.min(Math.max(characterPosition, 0), text.length());
            while (cursor < target) {
                if (text.charAt(cursor) == '\n') {
                    line++;
                }
                cursor++;
            }
            return line;
        }
    }
}
