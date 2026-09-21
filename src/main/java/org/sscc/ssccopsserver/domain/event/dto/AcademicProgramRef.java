package org.sscc.ssccopsserver.domain.event.dto;

/*
 * 행사 응답에 실리는 «이 행사는 학술 프로그램이다» 표시 (#519 · ssccops#435 · ADR-0043).
 *
 * 행사 응답 넷(공개 목록·상세, 운영 목록·상세)이 같은 모양으로 갖고, **학술 프로그램이 아닌
 * 행사에서는 null이다** — 서버가 «일반 행사» 같은 대체값을 만들지 않는다(AP-15 · #198의
 * eventPtcpId null 규칙과 같은 태도).
 *
 * ── 왜 분류(event_clsf)가 아니라 별도 필드인가 ────────────────
 * 스터디·프로젝트·트랙은 «어떤 종류의 행사인가»(세미나·행사·모집)가 아니라 **«행사가 아니라
 * 회차가 있는 프로그램인가»**라는 다른 차원의 질문이다. 그 사실의 정본은 운영진이 화면에서
 * 고치는 분류가 아니라 acdm_actv 행의 존재(event 1:1 확장 · ADR-0043은 이를 acdm_prgrm이라
 * 부른다)이고, 분류로 가르는 안(«학술 활동» 분류 시드)은 dev에서 이관 행사 4건이 EVENT 2 ·
 * RECRUIT 2로 이미 갈린 실측으로 기각됐다 — 운영 데이터에 기대는 구분은 한 학기 안에
 * 흐트러졌고, 구조에 기대는 구분은 흐트러질 길이 없다.
 *
 * ── 왜 유형 어휘가 acdm_actv_type인가 ─────────────────────────
 * typeCd·typeNm은 새 코드테이블이 아니라 학술 유형(acdm_actv_type)의 코드·이름 그대로다.
 * 유형이 늘어도(V19 트랙) 이 코드는 바뀌지 않고, 같은 구분을 두 코드테이블이 나눠 갖는 상태
 * (이관 주석이 경계한 «스터디·프로젝트 어휘 두 벌»)를 만들지 않는다.
 *
 * 값을 채우는 것은 행사 도메인이 아니라 학술 도메인이다(AcademicEventLinkProvider) — 행사는
 * 이 모양을 선언만 하고, 학술 저장소를 직접 부르면 순환이다(DomainCycleTest).
 */
public record AcademicProgramRef(Long academicProgramId, String typeCd, String typeNm) {}
