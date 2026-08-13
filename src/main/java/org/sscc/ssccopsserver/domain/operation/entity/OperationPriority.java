package org.sscc.ssccopsserver.domain.operation.entity;

/*
 * oper.prrty_rnk_cd — 운영 건 우선순위 코드. 화면의 높음/보통/낮음 3종에 대응한다.
 * 화면에서 항상 하나가 선택돼 있고 기본값이 '보통'이라 미지정 요청은 NORMAL로 저장한다.
 */
public enum OperationPriority {
    HIGH, // 높음
    NORMAL, // 보통
    LOW // 낮음
}
