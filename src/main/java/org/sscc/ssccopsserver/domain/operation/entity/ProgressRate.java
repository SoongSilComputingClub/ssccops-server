package org.sscc.ssccopsserver.domain.operation.entity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/*
 * 진행률 계산 규칙을 모아둔 도메인 값. 엔티티가 아니라 규칙만 담는다.
 *
 * 상위 업무 상세(OPS-003)가 쓰는 두 계산이 여기 함께 있다. 하위 업무 진행률은
 * 체크리스트 완료율에서 나오고(REQ-021이 체크리스트를 완료 판정의 단일 근거로 두므로
 * sub_work에 진행률 컬럼을 따로 만들지 않았다), 상위 업무 진행률은 그 값들의 평균이다.
 * 두 계산이 한 곳에 있어야 나중에 목록·대시보드가 같은 값을 다르게 세지 않는다.
 *
 * 주의: 여기서 나온 값은 work.work_prgrs_rt에 쓰이지 않는다. 조회는 어떤 상태도 바꾸지
 * 않으므로(AP-07) 저장 컬럼은 그대로 두고 응답에서만 계산한다. 저장 컬럼을 다른 식으로
 * 채우던 코드는 #117에서 걷어냈으므로(AGG-05) 이제 그 컬럼은 등록 시의 0에 머문다 —
 * 진행률의 정본은 이 계산 하나뿐이다.
 *
 * 소수 2자리는 work_prgrs_rt가 NUMERIC(5,2)라 그 이상은 어차피 담기지 않기 때문이다.
 */
public final class ProgressRate {

    private static final int SCALE = 2;
    private static final BigDecimal PERCENT_MULTIPLIER = BigDecimal.valueOf(100);

    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE);
    public static final BigDecimal COMPLETE = PERCENT_MULTIPLIER.setScale(SCALE);

    private ProgressRate() {}

    /*
     * 체크리스트 완료율. 완료된 건은 항목 수와 무관하게 100이다 —
     * 승인이 필요 없는 유형은 완료 점검 항목이 하나도 없을 수 있고(TR-03은 '남은 항목 0건'만
     * 보므로 그대로 완료된다), 그런 건이 완료되고도 0%로 보이면 안 된다.
     *
     * 항목 수에 따라 값이 계단식으로 움직인다. 항목이 3개면 0·33.33·66.67·100뿐이다.
     */
    public static BigDecimal ofChecklist(boolean completed, long completedItems, long totalItems) {
        if (completed) {
            return COMPLETE;
        }
        if (totalItems <= 0) {
            return ZERO;
        }
        return BigDecimal.valueOf(completedItems)
                .multiply(PERCENT_MULTIPLIER)
                .divide(BigDecimal.valueOf(totalItems), SCALE, RoundingMode.HALF_UP);
    }

    // 하위 업무 진행률의 단순 평균. 하위 업무가 하나도 없으면 0이다
    public static BigDecimal average(List<BigDecimal> rates) {
        if (rates.isEmpty()) {
            return ZERO;
        }
        BigDecimal sum = rates.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(rates.size()), SCALE, RoundingMode.HALF_UP);
    }
}
