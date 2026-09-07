package org.sscc.ssccopsserver.domain.academicprogram.dto;

import java.util.Arrays;
import java.util.Locale;

import org.sscc.ssccopsserver.global.apipayload.code.error.CommonErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

/*
 * 목록 조회(#131 · GET /v1/academic-programs)의 mine 파라미터가 "나"를 어느 역할로 보는가
 * (#215).
 *
 * mine=true는 처음부터 **스터디장 OR 기획안 제출자**였다(AcademicProgramRepositoryImpl). 반면
 * 응답의 isLeader는 리더 본인일 때만 참이라(AcademicProgramSummaryResponse.of), 두 값의
 * 기준이 다르다 — 기획안을 내서 활동이 만들어졌지만 스터디장으로 지정되지는 않은 회원은
 * mine=true가 1건 이상을 돌려주면서 isLeader는 전부 false다. "mine 결과가 비어 있지 않으면
 * 스터디장"으로 판정하면 그 사람에게 스터디장 화면이 통째로 열린다(ssccops-web#224 초안이
 * 실제로 그렇게 적혔다가 이 코드를 읽고 고쳤다).
 *
 * 그래서 이름 대신 값으로 가른다 — 판정에 쓸 역할을 요청이 직접 말한다. **mine=true의 뜻은
 * 그대로 둔다**(ANY): 어드민 활동 목록과 lms 대시보드가 이미 그 뜻으로 쓰고 있어, 여기서
 * 의미를 좁히면 호출부가 조용히 깨진다.
 *
 * 필터를 끄는 표기(mine 없음·빈 값·false)는 null로 접는다 — Boolean이던 시절 mine=false가
 * "필터 없음"이었던 것을 그대로 유지한다. 그 밖의 표기는 기본값으로 떨어뜨리지 않고
 * INVALID_CODE_VALUE로 거절한다(AcademicProgramSortOrder.from과 같은 판단 — 오타 난 필터로
 * 목록을 받으면 클라이언트는 서버가 걸러 준 줄 알고 그대로 그린다).
 */
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public enum AcademicProgramMineRole {
    /** 스터디장 또는 기획안 제출자 — mine=true의 뜻이며 지금까지의 동작이다 */
    ANY("true"),
    /** 스터디장/팀장 본인. 응답의 isLeader가 참인 활동과 같은 집합이다 */
    LEADER("leader"),
    /** 기획안 제출자 본인 */
    PROPOSER("proposer");

    // 필터를 걸지 않는 표기. Boolean 바인딩 시절의 mine=false와 같은 뜻이다
    private static final String DISABLED = "false";

    private final String parameter;

    /** 필터를 걸지 않으면 null이다(파라미터 없음·빈 값·false). */
    public static AcademicProgramMineRole from(String parameter) {
        if (parameter == null || parameter.isBlank()) {
            return null;
        }
        String normalized = parameter.strip().toLowerCase(Locale.ROOT);
        if (DISABLED.equals(normalized)) {
            return null;
        }
        return Arrays.stream(values())
                .filter(role -> role.parameter.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new GeneralException(CommonErrorCode.INVALID_CODE_VALUE));
    }
}
