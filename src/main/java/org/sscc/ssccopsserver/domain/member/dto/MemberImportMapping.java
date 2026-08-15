package org.sscc.ssccopsserver.domain.member.dto;

import java.util.EnumMap;
import java.util.Map;

import org.sscc.ssccopsserver.domain.member.code.MemberImportField;
import org.sscc.ssccopsserver.domain.member.code.error.MemberErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

/*
 * 컬럼 매핑 (#84). 요청의 `{"이름":"mbrNm","학번":"stdntNo","비고":""}`를 검증한 결과다.
 *
 * 헤더 문자열이 아니라 **컬럼 위치**를 들고 있는 것이 요점이다. 같은 헤더가 두 번 나오는 파일에서
 * 이름으로 값을 꺼내면 어느 컬럼인지가 매번 달라진다 — 자리를 한 번 굳혀 두면 행마다 같은 칸을 본다.
 *
 * 빈 문자열은 '매핑하지 않음'이고 오류가 아니다. 위저드가 모든 헤더를 항목으로 그려 두고 고르지
 * 않은 것을 ""로 보내기 때문이다.
 */
public record MemberImportMapping(Map<MemberImportField, Integer> columnIndexByField) {

    /*
     * 매핑이 성립하는지 확인하고 자리로 굳힌다. 어긋난 매핑은 행별 오류가 아니라 요청 전체를
     * 400 CSV_MAPPING_INVALID로 거절한다 — 매핑이 틀리면 모든 행이 같은 이유로 틀려, 128건짜리
     * 오류 목록만 남고 무엇을 고쳐야 하는지는 어디에도 없다.
     *
     * 세 가지를 본다:
     *  1. 파일에 없는 헤더를 가리키는 매핑 — 다른 파일로 만든 매핑을 그대로 보낸 경우다
     *  2. 한 필드에 두 컬럼 — 어느 쪽을 쓸지 서버가 고를 근거가 없다
     *  3. 필수 필드(mbrNm·mbrGrdCd·mbrSttsCd) 누락
     * 알 수 없는 필드 key도 1과 같은 자리에서 걸린다(조용히 무시하면 화면에서 매핑한 컬럼이
     * 사라진 채 "정상" 응답이 돌아간다).
     */
    public static MemberImportMapping of(Map<String, String> rawMapping, MemberImportCsv csv) {
        Map<MemberImportField, Integer> resolved = new EnumMap<>(MemberImportField.class);
        if (rawMapping != null) {
            rawMapping.forEach((header, fieldKey) -> putResolved(resolved, header, fieldKey, csv));
        }

        for (MemberImportField field : MemberImportField.values()) {
            if (field.isMappingRequired() && !resolved.containsKey(field)) {
                throw new GeneralException(MemberErrorCode.CSV_MAPPING_INVALID);
            }
        }
        return new MemberImportMapping(resolved);
    }

    private static void putResolved(
            Map<MemberImportField, Integer> resolved,
            String header,
            String fieldKey,
            MemberImportCsv csv) {

        if (fieldKey == null || fieldKey.isBlank()) {
            return;
        }

        MemberImportField field =
                MemberImportField.fromKey(fieldKey)
                        .orElseThrow(
                                () -> new GeneralException(MemberErrorCode.CSV_MAPPING_INVALID));

        int columnIndex = csv.indexOfHeader(header == null ? "" : header.trim());
        if (columnIndex < 0 || resolved.putIfAbsent(field, columnIndex) != null) {
            throw new GeneralException(MemberErrorCode.CSV_MAPPING_INVALID);
        }
    }

    /** 매핑되지 않은 필드는 언제나 빈 값이다 — 미입력과 같게 다룬다 */
    public String valueOf(MemberImportField field, MemberImportCsvRow row) {
        Integer columnIndex = columnIndexByField.get(field);
        return columnIndex == null ? "" : row.valueAt(columnIndex);
    }
}
