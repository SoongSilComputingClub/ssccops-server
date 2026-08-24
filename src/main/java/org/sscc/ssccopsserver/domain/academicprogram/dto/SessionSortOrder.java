package org.sscc.ssccopsserver.domain.academicprogram.dto;

import java.util.Arrays;

import org.sscc.ssccopsserver.domain.academicprogram.entity.SessionEntity;
import org.sscc.ssccopsserver.global.apipayload.code.error.CommonErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

/*
 * 회차 목록(#135)의 sort 파라미터. 표기는 AcademicProgramSortOrder와 같다 — 내림차순은 필드명
 * 앞의 '-'다.
 *
 * 기본이 seqno 오름차순인 것은 이 목록이 활동 상세 안의 회차 이력 표이기 때문이다 — 사람이
 * 읽는 순서는 등록 순서(PK)도 진행일도 아니라 회차 번호다(커리큘럼 조회 #134와 같은 이유).
 *
 * 두 정렬 키 모두 NULL일 수 없다. seqno는 curriculum_item의 NOT NULL이고 realDt는
 * SessionSubmitRequest가 @NotNull로 요구하므로, AcademicProgramSortOrder와 마찬가지로 nulls
 * last 처리가 필요 없다. 정렬 값을 문자열로 다루는 것은 두 키의 타입(정수·날짜)이 달라서이며,
 * 커서 비교는 DB가 원래 타입으로 하고 문자열은 커서 왕복 표현일 뿐이다.
 */
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public enum SessionSortOrder {
    SEQNO_ASC("seqno", SortKey.SEQNO, false),
    SEQNO_DESC("-seqno", SortKey.SEQNO, true),
    REAL_DT_ASC("realDt", SortKey.REAL_DT, false),
    REAL_DT_DESC("-realDt", SortKey.REAL_DT, true);

    public static final SessionSortOrder DEFAULT = SEQNO_ASC;

    private final String parameter;
    private final SortKey key;
    private final boolean descending;

    /*
     * 알 수 없는 표기는 조용히 기본값으로 떨어뜨리지 않는다 — 오타 난 정렬로 목록을 받으면
     * 클라이언트는 서버가 정렬해 준 줄 알고 그대로 그린다(AcademicProgramSortOrder와 같은 판단).
     */
    public static SessionSortOrder from(String parameter) {
        if (parameter == null || parameter.isBlank()) {
            return DEFAULT;
        }
        return Arrays.stream(values())
                .filter(order -> order.parameter.equals(parameter.strip()))
                .findFirst()
                .orElseThrow(() -> new GeneralException(CommonErrorCode.INVALID_CODE_VALUE));
    }

    public String sortValueOf(SessionEntity session) {
        return key == SortKey.REAL_DT
                ? session.getRealDate().toString()
                : String.valueOf(session.getCurriculumItem().getSeqno());
    }

    public enum SortKey {
        SEQNO,
        REAL_DT
    }
}
