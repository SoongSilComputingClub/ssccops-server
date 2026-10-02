package org.sscc.ssccopsserver.domain.academicprogram.repository;

/*
 * 활동별 진행률 재료 (#609 · 계획 항목 수와 그중 승인된 회차 수).
 *
 * 목록은 카드마다 progressRatio를 싣는데, 카드 수만큼 세면 그대로 N+1이다(DB-13 ·
 * SessionAttendanceCount 선례). 페이지의 활동 id로 묶어 한 번의 집계 질의로 받아오기 위한
 * 프로젝션이며, 상세도 id 하나로 같은 질의를 지난다 — 두 화면이 같은 값을 세게 하기 위해서다.
 *
 * 계획 항목이 하나도 없는 활동은 GROUP BY 결과에 나오지 않는다 — 0/0으로 채우는 것은 호출부다.
 */
public interface AcademicProgramProgressCount {

    Long getAcademicProgramId();

    long getCurriculumItemCount();

    long getApprovedSessionCount();
}
