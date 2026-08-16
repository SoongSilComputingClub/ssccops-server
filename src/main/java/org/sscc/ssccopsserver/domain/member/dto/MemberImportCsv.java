package org.sscc.ssccopsserver.domain.member.dto;

import java.util.List;

/*
 * 파싱된 CSV 한 벌 (#84). 헤더 한 줄과 데이터 행들이다.
 *
 * 파서의 출력이자 미리보기·검증 양쪽의 입력이다 — 두 엔드포인트가 같은 파싱 결과를 보아야
 * 화면에서 고른 컬럼과 서버가 읽는 컬럼이 갈리지 않는다.
 */
public record MemberImportCsv(List<String> headers, List<MemberImportCsvRow> rows) {

    /** 헤더 문자열의 컬럼 위치. 없으면 -1 */
    public int indexOfHeader(String header) {
        return headers.indexOf(header);
    }
}
