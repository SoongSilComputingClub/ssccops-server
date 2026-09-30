package org.sscc.ssccopsserver.domain.academicprogram.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;

/*
 * 계획 대비 승인 회차 진행률(#131 상세 응답의 progress · #609). 저장하지 않는 파생값이다 —
 * acdm_actv에 컬럼을 두면 회차 승인·재제출마다 갱신할 자리가 생겨 어긋난다.
 *
 * 진행률 = 승인 회차(sesn_stts_cd = APPROVED) ÷ 계획 항목(crclm_artcl) × 100, 소수 2자리.
 * 계산은 여기(of) 한 곳이고, 목록의 progressRatio도 이 값의 ratio를 그대로 싣는다 — 두 화면이
 * 같은 활동을 다른 숫자로 보이지 않게 하기 위해서다(work 도메인의 ProgressRate와 같은 원칙).
 * 재료(두 수)를 세는 것도 한 질의다(CurriculumItemRepository.countProgressByAcademicProgramIds).
 *
 * **분모는 계획이지 실적(sesn 행)이 아니다.** 실적으로 나누면 제출하지 않은 회차가 분모에서
 * 빠져, 한 번 기록하고 멈춘 활동이 100%가 된다. 분자는 승인된 것만 센다 — 제출됨(SUBMITTED)은
 * 학술국장이 아직 보지 않은 기록이다. sesn.crclm_artcl_id가 UNIQUE라 100을 넘지 않는다.
 *
 * ⚠️ totalSessionCount는 이름과 달리 **계획 항목 수**다(웹 상세가 «승인 / 전체»로 쓴다).
 * 필드 이름은 API 계약이라 그대로 둔다 — 상세의 curriculumItemCount와 언제나 같은 값이다.
 *
 * #131~#608 동안 이 값은 언제나 0/0/0이었다 — #131 시점에 Session 엔티티가 없어 zero()로
 * 만들었고, #135가 Session을 세운 뒤에도 계산이 붙지 않았다(#609).
 */
public record AcademicProgramProgressResponse(
        int totalSessionCount, int approvedSessionCount, BigDecimal ratio) {

    private static final int SCALE = 2;
    private static final BigDecimal PERCENT_MULTIPLIER = BigDecimal.valueOf(100);

    private static final AcademicProgramProgressResponse ZERO =
            new AcademicProgramProgressResponse(0, 0, BigDecimal.ZERO.setScale(SCALE));

    // 계획 항목이 하나도 없는 활동. 나눌 것이 없으므로 0이다
    public static AcademicProgramProgressResponse zero() {
        return ZERO;
    }

    public static AcademicProgramProgressResponse of(
            long curriculumItemCount, long approvedSessionCount) {
        if (curriculumItemCount <= 0) {
            return ZERO;
        }
        BigDecimal ratio =
                BigDecimal.valueOf(approvedSessionCount)
                        .multiply(PERCENT_MULTIPLIER)
                        .divide(
                                BigDecimal.valueOf(curriculumItemCount),
                                SCALE,
                                RoundingMode.HALF_UP);
        return new AcademicProgramProgressResponse(
                Math.toIntExact(curriculumItemCount), Math.toIntExact(approvedSessionCount), ratio);
    }
}
