package org.sscc.ssccopsserver.domain.form.code;

import java.util.Arrays;
import java.util.Optional;

/*
 * 운영진이 화면에서 «이 폼으로 하겠다»고 지정할 수 있는 시스템 폼 코드 (#520 · ssccops#436 ·
 * ADR-0044). PUT /v1/forms/system/{sysFormCd}의 허용 목록이며 **지금은 RECRUIT 하나다.**
 *
 * sys_form_cd는 문자열 컬럼이고 코드가 폼을 찾는 열쇠라(#140) 아무 값이나 붙일 수 있게 두면
 * 기획안(PROPOSAL)처럼 시드와 계약(SystemFormContract)이 세운 폼의 포인터를 화면 조작 한 번으로
 * 옮길 수 있다 — 그 순간 승인 이관이 다른 폼의 답을 읽는다. 그래서 «지정할 수 있는 코드»를
 * 서버가 열거하고 나머지는 400 SYSTEM_FORM_NOT_DESIGNATABLE로 끊는다. PROPOSAL이 여기 없는
 * 것이 요점이다: 그 폼은 시드가 세우고 계약이 잠그는 폼이라 운영진이 고르는 대상이 아니다.
 *
 * 문자열 상수 집합이 아니라 enum인 것은 코드가 늘 때 «지정 가능한가»를 묻는 자리(서비스)와
 * 값을 적는 자리(여기)가 하나로 남게 하기 위해서다. SystemFormContract에 «계약 없음»으로
 * 한 줄을 더하는 안은 기각했다 — 그 표는 «코드가 읽는 문항»의 선언이라 비어 있는 줄이 뜻을
 * 갖지 않고, 지정 가능 여부는 계약 유무와 다른 축이다(계약이 있어도 지정을 열 수 있고, 계약이
 * 없어도 닫을 수 있다).
 *
 * 신입회원 모집 폼(RECRUIT)에는 계약이 없다. 학기마다 문항이 바뀌는 지원서라 코드가 읽는
 * 문항이 없고, 그래서 문항 잠금(#498)은 «계약이 있는 시스템 폼»으로 좁혀졌다 — 지정된 동안
 * 잠기는 것은 삭제뿐이다(FormEntity.requireDeletable).
 */
public enum DesignatableSystemForm {
    /** 신입회원 모집 지원서. 익명 층의 /join이 GET /public/v1/forms/system/RECRUIT/meta로 읽는다 */
    RECRUIT("RECRUIT");

    private final String code;

    DesignatableSystemForm(String code) {
        this.code = code;
    }

    /** sys_form_cd에 저장되는 값 */
    public String code() {
        return code;
    }

    /** 경로 변수가 허용 목록에 있는가. 없으면 빈 Optional이며 호출부가 400으로 바꾼다 */
    public static Optional<DesignatableSystemForm> of(String code) {
        return Arrays.stream(values()).filter(value -> value.code.equals(code)).findFirst();
    }
}
