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
}
