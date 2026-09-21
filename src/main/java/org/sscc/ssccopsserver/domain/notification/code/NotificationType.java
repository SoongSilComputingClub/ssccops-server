package org.sscc.ssccopsserver.domain.notification.code;

/*
 * noti.noti_type_cd — 알림의 종류 (ssccops#446 · ADR-0045).
 *
 * 1차 사건은 하위 업무 다섯이다 — 승인 요청·승인·반려는 전이(SubWorkTransitionedEvent)가,
 * 마감 D-1·첫 지연일은 09:00 스케줄러가 만든다. 값 이름은 #446의 API 계약(`type`)과 같다 —
 * 웹이 이 문자열로 아이콘·문구를 가르므로 바꾸면 web Sub-task에 알린다.
 *
 * **값 목록과 V21의 CHECK 제약이 정본 한 쌍이다**(V13 규칙 ·
 * FlywayMigrationValidateTest.checkConstraintsMatchTheirEnums). 사건을 더할 때 새 마이그레이션으로
 * 제약도 함께 넓힌다 — 기획안·회차 사건(lms)이 그 첫 후보다(#446 «하지 않는 것»).
 */
public enum NotificationType {

    /** 검토 요청 → 그 유형의 결재 권한 보유자 전원(요청자 제외) */
    APPROVAL_REQUESTED,

    /** 승인·완료 → 담당자 */
    APPROVAL_APPROVED,

    /** 반려 → 담당자 */
    APPROVAL_REJECTED,

    /** 마감이 내일(D-1) → 담당자. 하루 한 번, noti_key로 한 번만 */
    DEADLINE_DUE,

    /** 마감이 어제(첫 지연일) → 담당자. 하루 한 번, noti_key로 한 번만 */
    DEADLINE_OVERDUE
}
