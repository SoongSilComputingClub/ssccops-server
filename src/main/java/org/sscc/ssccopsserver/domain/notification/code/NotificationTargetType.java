package org.sscc.ssccopsserver.domain.notification.code;

/*
 * noti.trgt_type_cd — 알림이 가리키는 대상의 종류 (ssccops#446).
 *
 * 대상은 (`trgt_type_cd`, `trgt_id`) 두 값이고 **FK가 없다** — 공유 링크(`ShareTargetType`)·
 * 파일 참조와 같은 판단이다. 대상이 지워져도 «그때 그 알림이 갔다»는 남아야 하고, 대상 종류가
 * 늘 때마다 스키마가 바뀌면 안 된다. 화면은 `linkPath`로 이동하므로 대상 좌표는 «무엇에 대한
 * 알림인가»를 묻는 화면(같은 대상의 알림 묶기 등)을 위한 값이다.
 *
 * 값 목록과 V21의 CHECK 제약이 정본 한 쌍이다.
 */
public enum NotificationTargetType {

    /** 하위 업무 — 1차 사건의 전부 */
    SUB_WORK
}
