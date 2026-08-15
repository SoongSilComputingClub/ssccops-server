package org.sscc.ssccopsserver.domain.member.code;

/*
 * CSV 이관 검증의 행별 판정 (#84).
 *
 * 세 값은 **서로 겹치지 않는다** — 요약의 okCount·errorCount·duplicateCount를 더하면 totalCount가
 * 되어야 화면의 통계가 성립하기 때문이다(이슈의 응답 예시가 그 관계를 전제한다). 그래서 오류와
 * 중복이 함께 있는 행은 ERROR 하나로 세고, 사유(reasons)에는 둘 다 담는다.
 *
 * 경고(WARNING)는 여기 없다. 경고는 행의 판정이 아니라 판정에 딸린 표시이며, 경고가 있는 행도
 * 이관은 진행되므로 OK로 남아 okCount에 들어간다 — 상태 값으로 만들면 "경고인데 통과"라는 것을
 * 화면이 다시 해석해야 하고, 세 버킷의 합이 무너진다.
 */
public enum MemberImportRowStatus {

    /** 그대로 이관할 수 있는 행. 경고만 있는 행도 여기다 */
    OK,

    /** 값이 규칙을 어겨 이관할 수 없는 행 */
    ERROR,

    /** 값 자체는 성립하지만 학번이 mbr에 이미 있거나 같은 파일 안에서 겹치는 행 (BR-M40 — 자동 병합은 없다) */
    DUPLICATE
}
