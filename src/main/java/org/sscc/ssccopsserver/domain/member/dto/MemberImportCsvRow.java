package org.sscc.ssccopsserver.domain.member.dto;

import java.util.List;

/*
 * 파싱된 CSV 데이터 행 하나 (#84).
 *
 * rowNo는 **원본 파일의 물리적 줄 번호**다 — 헤더가 1행이므로 첫 데이터 행은 보통 2행이다.
 * 레코드 순번이 아니라 줄 번호인 것은 운영자가 파일을 열어 그 줄을 찾아야 하기 때문이며,
 * 그래서 줄바꿈을 포함한 필드가 있으면 두 값이 갈린다(레코드 3번이 7행일 수 있다).
 *
 * values는 헤더와 같은 순서의 컬럼 값이며, 행의 컬럼 수가 헤더보다 적을 수 있다(꼬리 컬럼 생략).
 * 그 경우 없는 컬럼은 빈 값으로 읽는다 — 행 길이 불일치를 오류로 끊으면 엑셀이 만들어 내는
 * 흔한 파일이 통째로 거절된다.
 */
public record MemberImportCsvRow(long rowNo, List<String> values) {

    /** 범위를 벗어난 컬럼은 빈 문자열이다. 값이 없는 것과 컬럼이 없는 것을 같게 다룬다 */
    public String valueAt(int columnIndex) {
        if (columnIndex < 0 || columnIndex >= values.size()) {
            return "";
        }
        String value = values.get(columnIndex);
        return value == null ? "" : value;
    }
}
