package org.sscc.ssccopsserver.domain.operation.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class WorkEntityTest {

    private WorkEntity work() {
        return WorkEntity.create(null, WorkType.EVENT, null);
    }

    @Test
    void progressRateStartsAtZero() {
        assertThat(work().getProgressRate()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void recalculateProgressRateUsesCompletedRatioAsPercent() {
        WorkEntity work = work();

        work.recalculateProgressRate(1, 4);

        assertThat(work.getProgressRate()).isEqualByComparingTo(new BigDecimal("25.00"));
    }

    // work_prgrs_rt가 NUMERIC(5,2)이라 나누어떨어지지 않는 비율은 소수 2자리로 반올림한다
    @Test
    void recalculateProgressRateRoundsToTwoDecimals() {
        WorkEntity work = work();

        work.recalculateProgressRate(2, 3);

        assertThat(work.getProgressRate()).isEqualByComparingTo(new BigDecimal("66.67"));
    }

    @Test
    void recalculateProgressRateWithoutSubWorksIsZero() {
        WorkEntity work = work();
        work.recalculateProgressRate(3, 3);

        work.recalculateProgressRate(0, 0);

        assertThat(work.getProgressRate()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
