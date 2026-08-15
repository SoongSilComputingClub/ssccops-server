package org.sscc.ssccopsserver.domain.member.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;

import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.code.error.CommonErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

/*
 * 회원 목록 조회의 sort 파라미터 (#76). 내림차순은 필드명 앞의 '-'로 표기한다 (AP-13).
 *
 * 표기는 데이터사전의 컬럼명(mbr_nm·gen_no·join_ymd·mdfcn_dt)을 카멜케이스로 옮긴 것이며
 * 웹의 정렬 토글 4종과 1:1로 대응한다. 토글은 같은 열을 두 번 누르면 방향이 뒤집히므로
 * 키 하나에 오름/내림 두 값을 둔다.
 *
 * WorkSortOrder와 형태가 같지만 정렬 키가 다르고 키의 타입도 넷이 서로 다르다(문자열·정수·
 * 날짜·시각). 커서가 그 값을 실어 나르므로 문자열로 굳히고 되돌리는 일을 이 enum이 맡는다.
 *
 * JPQL 경로는 여기 두지 않는다 — 이 enum은 API 계약이고 매핑은 Repository의 몫이다 (LY-03).
 */
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public enum MemberSortOrder {
    NAME_ASC("mbrNm", SortKey.NAME, false),
    NAME_DESC("-mbrNm", SortKey.NAME, true),
    GENERATION_ASC("genNo", SortKey.GENERATION, false),
    GENERATION_DESC("-genNo", SortKey.GENERATION, true),
    JOIN_DATE_ASC("joinYmd", SortKey.JOIN_DATE, false),
    JOIN_DATE_DESC("-joinYmd", SortKey.JOIN_DATE, true),
    UPDATED_AT_ASC("mdfcnDt", SortKey.UPDATED_AT, false),
    UPDATED_AT_DESC("-mdfcnDt", SortKey.UPDATED_AT, true);

    /*
     * 기본값은 이름 오름차순이다. 회원 관리 화면은 '명부'라 사람을 찾으러 들어오는 곳이고,
     * 등록 최신순이 기본인 업무 목록(AGG-06)과 성격이 다르다.
     */
    public static final MemberSortOrder DEFAULT = NAME_ASC;

    private final String parameter;
    private final SortKey key;
    private final boolean descending;

    /*
     * 쿼리 파라미터 표기를 enum으로 바꾼다. 값을 생략하면 기본 정렬이다.
     *
     * 알 수 없는 표기는 조용히 기본값으로 떨어뜨리지 않는다 — 오타 난 정렬로 목록을 받으면
     * 클라이언트는 서버가 정렬해 준 줄 알고 그대로 그린다 (WorkSortOrder와 같은 판단).
     */
    public static MemberSortOrder from(String parameter) {
        if (parameter == null || parameter.isBlank()) {
            return DEFAULT;
        }
        return Arrays.stream(values())
                .filter(order -> order.parameter.equals(parameter.strip()))
                .findFirst()
                .orElseThrow(() -> new GeneralException(CommonErrorCode.INVALID_CODE_VALUE));
    }

    /*
     * 다음 커서에 실을 정렬 키 값. 네 키가 서로 다른 타입이라 커서 표기인 문자열로 굳힌다.
     *
     * 네 컬럼 모두 NOT NULL이므로 NULL 자리표시자를 두지 않는다 — 업무 목록이 nulls last를
     * 신경 쓰는 것과 갈리는 지점이다(그쪽 정렬 키인 oper.bgng_dt는 nullable이다).
     */
    public String sortValueOf(MemberEntity member) {
        return switch (key) {
            case NAME -> member.getName();
            case GENERATION -> String.valueOf(member.getGenerationNumber());
            case JOIN_DATE -> member.getJoinDate().toString();
            case UPDATED_AT -> member.getUpdatedAt().toString();
        };
    }

    /*
     * 커서에 실려 온 문자열을 비교 가능한 값으로 되돌린다. 문자열 그대로 비교하면 기수는
     * 사전순이 되어 10이 2보다 앞서고, 날짜·시각은 바인딩 타입을 Hibernate가 추론하지 못한다.
     *
     * 형식이 깨졌으면 예외를 던지고 MemberCursor가 INVALID_CURSOR로 옮긴다.
     */
    public Object parseSortValue(String raw) {
        return switch (key) {
            case NAME -> raw;
            case GENERATION -> Integer.valueOf(raw);
            case JOIN_DATE -> LocalDate.parse(raw);
            case UPDATED_AT -> Instant.parse(raw);
        };
    }

    /*
     * 정렬 대상 컬럼. 방향과 무관한 '무엇으로 정렬하는가'만 담는다 — 커서 비교식이
     * 오름/내림차순에서 갈리기 때문에 방향과 키를 따로 다뤄야 한다.
     */
    public enum SortKey {
        NAME,
        GENERATION,
        JOIN_DATE,
        UPDATED_AT
    }
}
