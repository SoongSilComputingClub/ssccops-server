package org.sscc.ssccopsserver.domain.operation.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class WorkEntityTest {

    private WorkEntity work() {
        return WorkEntity.create(null, WorkType.EVENT, null);
    }

    /*
     * 진행률 컬럼은 등록 시 0으로 굳고 그 뒤 갱신하지 않는다 (AGG-05, #117) —
     * 응답의 진행률은 ProgressRate.average가 조회 때 계산한다.
     */
    @Test
    void progressRateStartsAtZero() {
        assertThat(work().getProgressRate()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
