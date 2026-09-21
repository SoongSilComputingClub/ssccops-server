package org.sscc.ssccopsserver.domain.academicprogram.repository;

/*
 * «이 event에 딸린 학술 프로그램은 무엇인가»의 질의 결과 (#519 · ADR-0043).
 *
 * 행사 응답 넷이 academicProgram { academicProgramId, typeCd, typeNm }을 싣는데, 그 값을
 * 행사 목록 길이만큼 따로 물으면 N+1이다(DB-13 · SessionAttendanceCount 선례). event id 집합에
 * 대해 IN 한 번으로 받아오기 위한 프로젝션이며, 유형 이름은 acdm_actv_type 조인에서 온다 —
 * 엔티티를 통째로 끌어오지 않는 것은 필요한 것이 네 값뿐이고 AcademicProgramEntity의 나머지
 * 연관(제출자·리더·응답)이 따라오면 그것이 곧 다음 N+1이기 때문이다.
 *
 * 학술 프로그램이 아닌 event는 결과에 나오지 않는다 — null로 채우는 것은 호출부(행사 도메인)다.
 */
public interface AcademicProgramEventLink {

    Long getEventId();

    Long getAcademicProgramId();

    String getTypeCd();

    String getTypeNm();
}
