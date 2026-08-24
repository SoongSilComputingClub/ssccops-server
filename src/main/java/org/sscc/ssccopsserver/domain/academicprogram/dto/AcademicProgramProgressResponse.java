package org.sscc.ssccopsserver.domain.academicprogram.dto;

import java.math.BigDecimal;

/*
 * 계획 대비 승인 회차 진행률(#131 상세 응답의 progress). 저장하지 않는 파생값이다.
 *
 * 이 이슈(#131)에는 Session 엔티티가 아직 없다(#135에서 추가) — 진행률을 계산할 실적 자체가
 * 존재하지 않으므로 지금은 언제나 0/0/0이다. Session이 생기면 이 값을 계산하는 쪽은
 * 여기가 아니라 그 이슈의 서비스가 된다(work 도메인의 ProgressRate처럼 계산 규칙을 한 곳에
 * 모으는 원칙을 따른다).
 */
public record AcademicProgramProgressResponse(
        int totalSessionCount, int approvedSessionCount, BigDecimal ratio) {

    private static final AcademicProgramProgressResponse ZERO =
            new AcademicProgramProgressResponse(0, 0, BigDecimal.ZERO.setScale(2));

    public static AcademicProgramProgressResponse zero() {
        return ZERO;
    }
}
