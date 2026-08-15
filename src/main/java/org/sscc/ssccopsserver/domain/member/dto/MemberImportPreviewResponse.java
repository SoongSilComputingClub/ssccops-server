package org.sscc.ssccopsserver.domain.member.dto;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.sscc.ssccopsserver.domain.member.code.MemberImportField;

/*
 * CSV 이관 1단계 — 미리보기 응답 (POST /v1/members/imports/preview, #84).
 *
 * - headers: 파일의 첫 줄을 CSV 규칙대로 해석한 컬럼 이름. 순서가 곧 컬럼 위치다
 * - recommendedMapping: 헤더 → 대상 필드 key의 **추천**값. 짐작하지 못한 헤더는 ""로 들어간다
 *   (모든 헤더가 key를 갖고 있어야 위저드가 선택 상자를 그대로 그릴 수 있다)
 * - sampleRows: 앞 5행. headers와 같은 순서로 정렬된 값 배열이며, 헤더보다 짧은 행은
 *   빈 문자열로 채워 길이를 맞춘다 — 화면이 표를 그릴 때 칸이 밀리지 않게 하기 위해서다
 * - totalRowCount: 데이터 행 수(헤더 제외). 운영자가 "몇 건짜리 파일인가"를 미리 보게 한다
 *
 * 값을 Map이 아니라 배열로 내리는 것은 같은 헤더가 두 번 나오는 파일 때문이다 — key로 담으면
 * 한 컬럼이 조용히 사라지고, 화면의 표와 서버가 읽는 컬럼 수가 갈린다.
 */
public record MemberImportPreviewResponse(
        List<String> headers,
        Map<String, String> recommendedMapping,
        List<List<String>> sampleRows,
        int totalRowCount) {

    /** 미리보기에 싣는 행 수. 파일의 모양만 확인하는 자리라 전량을 내릴 이유가 없다 */
    public static final int SAMPLE_ROW_LIMIT = 5;

    public static MemberImportPreviewResponse from(MemberImportCsv csv) {
        List<String> headers = csv.headers();

        Map<String, String> recommended = new LinkedHashMap<>();
        for (String header : headers) {
            recommended.putIfAbsent(header, recommendFieldKey(header, recommended.values()));
        }

        List<List<String>> samples =
                csv.rows().stream()
                        .limit(SAMPLE_ROW_LIMIT)
                        .map(row -> alignTo(headers, row))
                        .toList();

        return new MemberImportPreviewResponse(headers, recommended, samples, csv.rows().size());
    }

    /*
     * 이미 추천된 필드는 다시 추천하지 않는다. '이름'과 '회원명'이 함께 있는 파일에서 둘 다
     * mbrNm으로 추천하면 그 매핑은 "한 필드에 두 컬럼"이라 400으로 거절된다 — 운영자가 고르기도
     * 전에 다음 단계가 막히는 셈이라, 뒤쪽 헤더는 비워 두고 고르게 한다.
     */
    private static String recommendFieldKey(String header, Collection<String> taken) {
        for (MemberImportField field : MemberImportField.values()) {
            if (field.matchesHeader(header) && !taken.contains(field.key())) {
                return field.key();
            }
        }
        return "";
    }

    private static List<String> alignTo(List<String> headers, MemberImportCsvRow row) {
        return IntStream.range(0, headers.size()).mapToObj(row::valueAt).toList();
    }
}
