package org.sscc.ssccopsserver.domain.member.service;

import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/*
 * "이 명부 회원이 이 사람인가"의 **유일한 구현** (#86 · ssccops#78 A안).
 *
 * 판정은 학번 · 회원명 · 연락처 3종 완전 일치이며, 셋 중 하나라도 어긋나면 실패다. 어느 항목이
 * 어긋났는지는 이 클래스가 돌려주지 않는다 — boolean 하나뿐인 것이 VR-M23을 코드로 못 박는
 * 방법이다(AcademicProfilePolicy가 '어느 필드가 비었는지' 목록을 돌려주는 것과 정반대이며,
 * 그쪽은 운영자가 128건을 고쳐야 하는 자리이고 이쪽은 본인 확인이라 기준이 다르다).
 *
 * ── 왜 정규화한 뒤에 비교하는가 ─────────────────────────────────
 * 연락처는 사람마다 하이픈을 넣기도 빼기도 한다. CSV 명부에는 '010-1111-2222'로 들어가 있는데
 * 본인은 '01011112222'로 치는 것이 정상이고, 그대로 비교하면 본인인데도 연결되지 않는다.
 * 회원명은 화면 입력의 앞뒤 공백이 붙어 오는 것이 흔하다.
 *
 * ── 왜 규칙이 서버 한 곳에 있어야 하는가 ────────────────────────
 * 화면이 스스로 정규화해 보내면 규칙이 두 벌이 된다. 웹이 하이픈만 지우고 서버는 공백까지
 * 지우는 식으로 조금이라도 갈리면, 같은 사람이 화면에서는 연결되고 API로는 연결되지 않는
 * (혹은 그 반대의) 상태가 생긴다. 명부 쪽 값도 같은 함수를 통과시켜야 하므로 규칙은 비교하는
 * 쪽인 서버에 있어야 한다.
 *
 * 값만 보고 답할 수 있는 규칙이라 스프링 빈이 아니라 정적 메서드다 (AcademicProfilePolicy와
 * 같은 이유).
 */
public final class MemberLinkPolicy {

    private MemberLinkPolicy() {}

    /*
     * 명부의 회원이 요청한 사람과 같은 사람인가.
     *
     * **비어 있는 값은 어떤 입력과도 일치하지 않는다.** 연락처가 NULL인 이관 회원(#84에서
     * 경고로만 남기고 통과시킨 행)이 여기 걸린다 — 정규화 결과가 양쪽 다 null이면 equals가
     * 참이 되어 연락처를 확인하지 않은 채 연결되고, A안이 연락처를 요구하는 이유가 통째로
     * 무너진다. 그 회원은 운영진이 연락처를 채워 넣기 전까지 어떤 입력으로도 연결되지 않으며,
     * 그것이 이 정책이 의도한 결과다.
     */
    public static boolean matches(
            MemberEntity member, String studentNumber, String name, String phoneNumber) {

        return equalsNormalized(
                        normalizeStudentNumber(member.getStudentNumber()),
                        normalizeStudentNumber(studentNumber))
                && equalsNormalized(normalizeName(member.getName()), normalizeName(name))
                && equalsNormalized(
                        normalizePhoneNumber(member.getPhoneNumber()),
                        normalizePhoneNumber(phoneNumber));
    }

    /*
     * 학번은 앞뒤 공백만 지운다. 숫자만 남기지 않는 것은 학번이 숫자라는 보장이 없기 때문이다 —
     * 데이터사전의 stdnt_no는 VARCHAR(20)이고 명부에 어떤 표기가 들어와 있는지는 이관 파일이
     * 정한다. 후보 조회의 WHERE 절도 이 값을 그대로 쓴다.
     */
    public static String normalizeStudentNumber(String studentNumber) {
        return trimToNull(studentNumber);
    }

    // 회원명은 앞뒤 공백만 지운다. 가운데 공백은 이름의 일부일 수 있어 건드리지 않는다
    public static String normalizeName(String name) {
        return trimToNull(name);
    }

    /*
     * 연락처는 **숫자만 남긴다.** 하이픈·공백·괄호가 섞여도 같은 번호로 보게 하려는 것이며,
     * 국가번호 표기(+82)까지 다루지는 않는다 — 명부도 가입 화면도 국내 표기 하나뿐이고,
     * 없는 표기를 미리 다루면 '010'과 '+8210'을 같게 볼지를 근거 없이 정하게 된다.
     */
    public static String normalizePhoneNumber(String phoneNumber) {
        if (phoneNumber == null) {
            return null;
        }
        StringBuilder digits = new StringBuilder(phoneNumber.length());
        for (int index = 0; index < phoneNumber.length(); index++) {
            char ch = phoneNumber.charAt(index);
            if (ch >= '0' && ch <= '9') {
                digits.append(ch);
            }
        }
        return digits.isEmpty() ? null : digits.toString();
    }

    // 한쪽이라도 비어 있으면 일치가 아니다 (null == null 을 일치로 보지 않는다)
    private static boolean equalsNormalized(String left, String right) {
        return left != null && left.equals(right);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
