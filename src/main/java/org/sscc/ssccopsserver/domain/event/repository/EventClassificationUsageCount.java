package org.sscc.ssccopsserver.domain.event.repository;

/*
 * 분류별 사용 행사 수 집계 결과 (ssccops#140 분류 목록).
 *
 * 화면은 이 값으로 삭제 버튼을 잠근다 — 0이 아니면 삭제가 409 EVENT_CLASSIFICATION_IN_USE로
 * 거절된다 (RoleClassificationRoleCount 선례).
 */
public interface EventClassificationUsageCount {

    String getEventClsfCd();

    long getEventCount();
}
