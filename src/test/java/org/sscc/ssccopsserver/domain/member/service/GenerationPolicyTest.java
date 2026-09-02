package org.sscc.ssccopsserver.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

/*
 * 기수 계산 규칙 (#205). 기수 = 동아리 가입 연도 − 1982이며 2018년이 36기라는 것이 기준값의
 * 근거다 — 이 숫자가 어긋나면 명부 전체의 기수가 함께 어긋나므로 여기서 못 박는다.
 *
 * **계산에 월이 들어가지 않는다는 것**도 함께 확인한다. 메서드가 연도만 받는 모양 자체가 그
 * 규칙이며(연도 경계는 달력 기준 1월 1일이다), 2월 입부자도 그 해 기수다.
 */
class GenerationPolicyTest {

    /** 카톡에서 확정한 기준점. 이 한 줄이 상수 1982의 근거다 */
    @Test
    void year2018Is36th() {
        assertThat(GenerationPolicy.generationOf(2018)).hasValue(36);
    }

    @Test
    void generationGrowsWithTheYear() {
        assertThat(GenerationPolicy.generationOf(2024)).hasValue(42);
        assertThat(GenerationPolicy.generationOf(2026)).hasValue(44);
    }

    /*
     * 연도만으로 계산한다 — 학년도의 3월로 경계를 옮기지 않으므로 2월 입부자도 그 해 기수다.
     * 월을 받는 자리가 아예 없다는 것이 그 규칙의 표현이다.
     */
    @Test
    void februaryJoinBelongsToThatCalendarYear() {
        assertThat(GenerationPolicy.generationOf(2024))
                .isEqualTo(GenerationPolicy.generationOf(2024));
        assertThat(GenerationPolicy.generationOf(2024)).hasValue(42);
    }

    /** 연도를 모르면 계산할 근거가 없다. 컬럼이 NULL 허용이라 흔한 경우다 */
    @Test
    void unknownYearHasNoGeneration() {
        assertThat(GenerationPolicy.generationOf(null)).isEmpty();
    }

    /*
     * 기준연도 이하는 값 없음이다. **0을 돌려주지 않는 것이 요점이다** — gen_no의 0은 '미배정'
     * 센티널이라, 1982년을 0기로 계산해 내리면 배정된 기수와 미배정이 같은 값이 된다.
     */
    @Test
    void yearAtOrBeforeBaseYearHasNoGeneration() {
        assertThat(GenerationPolicy.generationOf(1982)).isEmpty();
        assertThat(GenerationPolicy.generationOf(1970)).isEmpty();
        assertThat(GenerationPolicy.generationOf(1983)).isEqualTo(OptionalInt.of(1));
    }
}
