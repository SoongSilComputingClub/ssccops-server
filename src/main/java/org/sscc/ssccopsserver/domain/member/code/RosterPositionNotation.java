package org.sscc.ssccopsserver.domain.member.code;

import org.sscc.ssccopsserver.global.apipayload.code.error.CommonErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

/*
 * 회원명부 내보내기의 직책 표기법 (#674 · ssccops#598).
 *
 * 회장·부회장은 어느 표기법에서든 «회장»·«부회장»이고, 갈리는 것은 그 밖의 회원이다.
 *
 * - FEDERATION(동아리연합회 표기법 · 기본) — 전원 «정회원». 연합회는 회장·부회장 외에는 모두
 *   정회원으로 받는다. 등급 FULL(정회원)과는 이름만 같다 — 준회원·활동회원도 여기서는 «정회원»이다.
 * - SSCC(SSCC 표기법) — 그 회원의 대표 역할 이름(총무·국장·국원 …), 대표 역할이 없으면 빈칸.
 *
 * 켜고 끄는 불리언 하나(«실제 역할로 표기»)로 두지 않은 것은 끈 상태가 무엇인지 화면만 봐서는 알 수
 * 없어서다 — 화면이 이 두 이름과 설명을 라디오로 나란히 보인다(Story 결정).
 */
public enum RosterPositionNotation {
    FEDERATION,
    SSCC;

    /*
     * 쿼리 문자열을 enum으로 바꾼다. 비어 있으면 기본값(FEDERATION)이다 — 옵션을 건드리지 않은
     * 요청이 곧 연합회 제출용이어야 한다.
     *
     * 스프링의 enum 바인딩에 맡기지 않는 것은 실패가 «변환 실패» 문장의 400 VALIDATION_FAILED로
     * 나가기 때문이다. 회원 목록의 등급·상태 필터(MemberSearchCondition)처럼 기준 코드 위반은
     * INVALID_CODE_VALUE로 내려야 화면이 «허용값을 다시 확인»으로 안내할 수 있다.
     */
    public static RosterPositionNotation from(String value) {
        if (value == null || value.isBlank()) {
            return FEDERATION;
        }
        try {
            return valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            throw new GeneralException(
                    CommonErrorCode.INVALID_CODE_VALUE,
                    "positionNotation은 FEDERATION 또는 SSCC여야 합니다: " + value);
        }
    }
}
