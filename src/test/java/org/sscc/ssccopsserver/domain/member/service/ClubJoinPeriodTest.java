package org.sscc.ssccopsserver.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/*
 * 명부의 '동아리 가입 시기' 한 칸을 읽는 규칙 (#205).
 *
 * 확인의 중심은 셋이다 — **일(日)은 읽고 버린다**, **미입력은 오류가 아니라 빈 값이다**,
 * **짐작하지 않는다**(연도가 4자리가 아니면 형식 오류). 검증과 이관 실행이 이 파서 하나를
 * 공유하므로, 여기서 갈리면 검증을 통과한 행이 실행에서 다른 값으로 저장된다.
 */
class ClubJoinPeriodTest {

    /** 연-월-일 세 토막. 구분자는 명부마다 다르고 일은 버린다 */
    @Test
    void readsYearMonthDayAndDropsTheDay() {
        assertThat(parse("2020-03-02")).isEqualTo(new ClubJoinPeriod(2020, 3));
        assertThat(parse("2020.03.02")).isEqualTo(new ClubJoinPeriod(2020, 3));
        assertThat(parse("2020/3/2")).isEqualTo(new ClubJoinPeriod(2020, 3));
        assertThat(parse("2020년 3월 2일")).isEqualTo(new ClubJoinPeriod(2020, 3));
    }

    /** 연-월 두 토막. 이 컬럼이 실제로 담는 정밀도다 */
    @Test
    void readsYearAndMonth() {
        assertThat(parse("2020-03")).isEqualTo(new ClubJoinPeriod(2020, 3));
        assertThat(parse("2020.3")).isEqualTo(new ClubJoinPeriod(2020, 3));
        assertThat(parse("2020년 3월")).isEqualTo(new ClubJoinPeriod(2020, 3));
    }

    /*
     * 연도만 적힌 칸은 월을 비운다. 3월로 짐작하지 않는 것이 요점이다 — 학기 시작이 아니라
     * 중간에 들어온 사람도 있고, 짐작한 값은 저장된 뒤 사실과 구별되지 않는다.
     */
    @Test
    void yearOnlyLeavesMonthEmpty() {
        assertThat(parse("2020")).isEqualTo(new ClubJoinPeriod(2020, null));
        assertThat(parse("2020년")).isEqualTo(new ClubJoinPeriod(2020, null));
    }

    /*
     * 미입력은 **오류가 아니라 빈 값이다.** 옛 가입일 매핑은 이관일로 채웠지만, 이 컬럼은
     * 모르는 것을 모른다고 두기 위해 생겼다.
     */
    @Test
    void blankValueIsEmptyPeriodNotAnError() {
        assertThat(parse("")).isEqualTo(new ClubJoinPeriod(null, null));
        assertThat(parse("   ")).isEqualTo(new ClubJoinPeriod(null, null));
        assertThat(ClubJoinPeriod.parse(null)).isPresent();
    }

    /*
     * 짐작하지 않는다. '20-03'은 2020년일 수도 1920년일 수도 있고, 빗나간 짐작은 저장된 뒤에는
     * 아무도 알 수 없다 — 운영자가 명부를 고치게 하는 편이 낫다.
     */
    @Test
    void twoDigitYearAndTextAreFormatErrors() {
        assertThat(ClubJoinPeriod.parse("20-03")).isEmpty();
        assertThat(ClubJoinPeriod.parse("abc")).isEmpty();
        assertThat(ClubJoinPeriod.parse("2020-03-02-01")).isEmpty();
    }

    /** 버리는 값이라도 범위는 본다 — 13월·45일은 저장할 자리가 없는 값이 아니라 잘못 적힌 칸이다 */
    @Test
    void outOfRangeMonthOrDayIsAFormatError() {
        assertThat(ClubJoinPeriod.parse("2020-13")).isEmpty();
        assertThat(ClubJoinPeriod.parse("2020-00")).isEmpty();
        assertThat(ClubJoinPeriod.parse("2020-03-45")).isEmpty();
    }

    private static ClubJoinPeriod parse(String raw) {
        Optional<ClubJoinPeriod> parsed = ClubJoinPeriod.parse(raw);
        assertThat(parsed).isPresent();
        return parsed.orElseThrow();
    }
}
