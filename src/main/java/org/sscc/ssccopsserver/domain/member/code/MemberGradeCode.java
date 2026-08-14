package org.sscc.ssccopsserver.domain.member.code;

/*
 * 회원 등급(mbr_grd) 기준 코드.
 *
 * 등급은 회칙이 정하는 고정 어휘라 화면에서 늘어나지 않으므로 enum으로 굳힐 수 있다.
 * 반대로 역할·역할분류(role·role_clsf)는 화면에서 추가·삭제하는 사용자 관리 코드테이블이라
 * enum으로 두지 않고 시드로만 둔다.
 *
 * 여기에는 코드 문자열만 두고 명칭·표시순번은 data.sql이 갖는다 — 양쪽이 같은 사실을 말하면
 * 언젠가 어긋나고, 어긋났을 때 어느 쪽이 맞는지 알 수 없게 된다.
 * enum 이름을 코드값과 같게 두어 별도 매핑 표 없이 name()이 그대로 코드가 되게 했다.
 */
public enum MemberGradeCode {

    /** 가입 직후 부여되는 기본 등급 */
    TEMP,
    ASSOC,
    ACTIVE,
    FULL;

    public String code() {
        return name();
    }
}
