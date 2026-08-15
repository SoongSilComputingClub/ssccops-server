package org.sscc.ssccopsserver.domain.member.code;

/*
 * 회원 상태(mbr_stts) 기준 코드. 코드 문자열만 두는 이유는 MemberGradeCode와 같다.
 *
 * 학적 상태(재학·휴학·졸업)와 자격 상실(탈퇴·제명)이 한 컬럼에 섞여 있다. 데이터사전이 그렇게
 * 정의하고 있어 그대로 따르되, 둘을 가르는 판단은 쓰는 쪽에서 한다 —
 * 예를 들어 담당자 배정 가능 여부는 MemberServiceImpl이 정의한다.
 */
public enum MemberStatusCode {

    /** 가입 시 기본으로 쓰는 상태 */
    ENROLLED,
    LEAVE,
    MIL_LEAVE,
    GRADUATED,
    WITHDRAWN,
    EXPELLED;

    public String code() {
        return name();
    }

    /*
     * 회원가입 화면에서 고를 수 있는 상태인지. 화면이 재학·졸업 두 가지만 제시하고 있으며,
     * 탈퇴·제명 상태로 가입을 신청한다는 것 자체가 성립하지 않는다.
     * 휴학은 가입 후 상태 변경으로 다룬다 — 가입 시점에 학적 증빙을 받지 않기 때문이다.
     */
    public boolean isSignupSelectable() {
        return this == ENROLLED || this == GRADUATED;
    }

    /*
     * 학번·학과·학년을 필수로 요구하는 상태인지. 졸업 회원은 학번이 기억나지 않을 수 있고
     * 학과·학년도 현재 사실이 아니라 선택 입력이다.
     */
    public boolean requiresAcademicProfile() {
        return this == ENROLLED;
    }

    /*
     * 기준 코드 문자열을 enum으로 되돌린다. mbr_stts는 기준 코드 테이블이라 운영 중에 행이
     * 늘어날 수 있어, enum에 없는 코드를 만나면 예외 대신 null이다 — 학적 필수 규칙은 재학
     * 하나에만 걸리므로 모르는 상태는 규칙 밖으로 두는 것이 맞다.
     */
    public static MemberStatusCode from(String code) {
        for (MemberStatusCode value : values()) {
            if (value.name().equals(code)) {
                return value;
            }
        }
        return null;
    }
}
