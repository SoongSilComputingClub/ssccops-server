package org.sscc.ssccopsserver.domain.member.service;

import java.util.OptionalInt;

/*
 * "동아리 가입 연도로부터 기수는 몇인가"의 **유일한 구현** (#205).
 *
 *     기수 = 동아리 가입 연도 − 1982
 *
 * 2018년 = 36기가 이 값의 근거다(2026-08-31 확정). 연도 단위라 월·학기는 계산에 쓰이지 않고,
 * **연도 경계는 달력 기준 1월 1일**이다 — 학년도의 3월이 아니므로 2월 입부자도 그 해 기수다.
 *
 * **상수 1982는 여기에만 둔다.** year - 1982는 한 줄이라 웹에 복제하고 싶은 유혹이 크지만, 이
 * 저장소에는 판정 규칙이 두 벌이 되어 실제 버그가 난 전례가 있다(권한 판정·지연 배지). 기준값이
 * 바뀔 때 고칠 자리가 하나여야 하므로 웹은 값을 복제하지 않고 기수 계산 API로 묻는다.
 *
 * **이 클래스는 저장을 하지 않는다.** 계산해서 돌려줄 뿐이고, gen_no에 넣는 것은 사람이 확인한
 * 뒤다 — 추정한 기수가 사실과 같은 숫자로 남으면 나중에 어디부터 의심할지 알 수 없다(BR-M43).
 * 그래서 CSV 이관은 이 계산을 부르지 않는다(MemberImportField.GENERATION_NUMBER 주석).
 *
 * AcademicProfilePolicy와 같은 꼴로 **스프링 빈이 아니라 정적 유틸**이다 — 저장소가 필요 없는
 * 규칙에 주입을 요구하면 요청 DTO처럼 빈이 아닌 자리에서 이 규칙을 부를 수 없다.
 */
public final class GenerationPolicy {

    /*
     * 1983년 입부가 1기다. 이 값이 바뀌면 기존 회원의 기수가 통째로 어긋나므로, 바꾸는 일은
     * 상수를 고치는 것이 아니라 이미 배정된 gen_no를 어떻게 할지 정하는 일이다.
     */
    private static final int BASE_YEAR = 1982;

    private GenerationPolicy() {}

    /*
     * 동아리 가입 연도로 기수를 계산한다. 계산할 근거가 없으면 빈 값이다.
     *
     * 빈 값이 되는 경우는 둘이다. 연도를 모르거나(NULL 허용 컬럼이라 흔하다), 기준연도 이하라
     * 기수가 0 이하로 나오는 경우다. **0을 돌려주지 않는 것이 중요하다** — gen_no의 0은
     * '미배정' 센티널이라, 1982년을 0기로 계산해 내리면 배정된 기수와 미배정이 같은 값이 된다.
     *
     * boolean이나 null이 아니라 OptionalInt인 것은 부르는 쪽이 '계산할 수 없음'을 반드시
     * 마주치게 하기 위해서다 — 기본값으로 흘려보내면 그 순간 추정값이 사실 자리에 앉는다.
     */
    public static OptionalInt generationOf(Integer clubJoinYear) {
        if (clubJoinYear == null || clubJoinYear <= BASE_YEAR) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(clubJoinYear - BASE_YEAR);
    }
}
