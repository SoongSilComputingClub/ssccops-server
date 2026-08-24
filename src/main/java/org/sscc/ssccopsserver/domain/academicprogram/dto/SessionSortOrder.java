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
 * **계획일(curriculum_item.plan_dt)은 정렬 키에 없다.** 승인 대기 목록(#136)의 "오래 기다린
 * 건부터"를 계획일로 표현하고 싶었지만 그 컬럼은 nullable이라(데이터모델 §2) 커서 비교가
 * 성립하지 않는다 — NULL인 행은 어느 쪽에도 놓이지 않아 페이지 경계에서 조용히 사라진다.
 * 대신 그 목록은 진행일 오름차순을 기본값으로 쓴다. 실제로 검토를 기다린 시간에 더 가까운
 * 값이기도 하다(회차 행에는 제출 일시가 없다 — SessionEntity가 감사 컬럼을 두지 않는다).
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
        return from(parameter, DEFAULT);
    }

    /*
     * 기본값이 화면마다 다른 목록을 위한 오버로드(#136). 활동을 가로지르는 목록에서는 회차
     * 번호 오름차순이 뜻을 잃는다 — 여러 활동의 1회차가 앞에 뭉치고 같은 활동의 회차가 흩어진다.
     * 어휘(고를 수 있는 sort 값)는 그대로 두고 무엇을 먼저 보여줄지만 호출부가 정한다.
     */
    public static SessionSortOrder from(String parameter, SessionSortOrder fallback) {
        if (parameter == null || parameter.isBlank()) {
            return fallback;
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
