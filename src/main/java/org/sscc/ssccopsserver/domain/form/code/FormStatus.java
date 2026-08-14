package org.sscc.ssccopsserver.domain.form.code;

/*
 * form.form_stts_cd — 폼 상태 기준 코드.
 *
 * MemberGradeCode 선례를 따라 여기에는 코드 문자열만 두고 명칭·표시순번은 data.sql이 갖는다.
 * 양쪽이 같은 사실을 말하면 언젠가 어긋나고, 어긋났을 때 어느 쪽이 맞는지 알 수 없게 된다.
 * enum 이름을 코드값과 같게 두어 별도 매핑 표 없이 name()이 그대로 코드가 되게 했다.
 *
 * 어휘는 웹이 이미 화면에 쓰고 있는 shared/config/codes.ts의 FormSttsCd와 같다.
 * 상태 전이(DRAFT → OPEN → CLOSED) 규칙은 폼 CRUD가 붙는 #32에서 전이 메서드로 얹는다 —
 * 여기서 미리 만들면 화면이 요구하는 전이와 어긋난 채로 굳는다.
 */
public enum FormStatus {

    /** 작성 중. 공개 링크로 접근할 수 없다 */
    DRAFT,

    /** 접수 중. 접수 기간(rcpt_bgng_dt·rcpt_end_dt)과 함께 실제 응답 가능 여부를 정한다 */
    OPEN,

    /** 접수 종료 */
    CLOSED;

    public String code() {
        return name();
    }
}
