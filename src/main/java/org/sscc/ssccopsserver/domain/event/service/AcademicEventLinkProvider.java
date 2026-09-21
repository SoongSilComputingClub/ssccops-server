package org.sscc.ssccopsserver.domain.event.service;

import java.util.Collection;
import java.util.Map;

import org.sscc.ssccopsserver.domain.event.dto.AcademicProgramRef;

/*
 * 주어진 행사 중 학술 프로그램(학술 활동)인 것을 알려 주는 포트 (ssccops#242 · #519).
 *
 * ── 왜 행사 도메인이 인터페이스를 갖는가 ────────────────────────
 * 공개 목록·상세는 학술 event를 접수 중(ACCEPTING)일 때만 노출한다(#187). 일반 공지형 행사는
 * 접수 상태와 무관하게 그대로 보인다. 그 둘을 가르려면 "이 행사가 학술에서 온 것인가"를 알아야
 * 하는데, 판별 규칙(acdm_actv.event_id 1:1)의 주인은 학술 도메인이다.
 *
 * 행사가 AcademicProgramRepository를 직접 주입받으면 **event → academicprogram → event
 * 순환**이 된다(학술은 이관으로 행사를 만들므로 행사를 31번 부른다). 그래서 묻는 쪽이 모양을
 * 선언하고 소유한 쪽이 답한다 — SystemFormApprovalHook · SharePreviewProvider와 같은 구조다.
 *
 * ── 왜 «있는가»가 아니라 «무엇인가»를 답하는가 (#519 · ADR-0043) ──
 * 처음에는 학술 event의 id 집합(Set<Long>)만 답했다 — 공개 노출 판정(#187)과 삭제 가드(#347)에
 * 필요한 것이 존재 여부뿐이었다. ADR-0043이 행사 응답에 «학술 프로그램인가 · 어느 유형인가»
 * (academicProgram { academicProgramId, typeCd, typeNm })를 싣기로 하면서 같은 사실을 값까지
 * 묻게 됐고, 그래서 답이 Map이다. **Set 메서드를 따로 남기지 않은 것은** 같은 행사 집합에 같은
 * 질의를 두 번(있는가 · 무엇인가) 보내는 자리가 생기기 때문이다 — 존재 여부는 containsKey다.
 *
 * ── 왜 한 건씩이 아니라 묶음으로 묻는가 ────────────────────────
 * 목록은 이미 읽어 온 행사 전부에 대해 이 판별이 필요하다. 한 건씩 물으면 목록 길이만큼
 * 질의가 늘어난다(N+1) — 그래서 인자가 컬렉션이고 구현은 IN 하나로 끝낸다. 단건(상세·수정·
 * 삭제)도 같은 메서드를 원소 하나로 부른다 — 단건 전용 메서드를 두면 두 질의가 갈릴 자리가 생긴다.
 */
public interface AcademicEventLinkProvider {

    /*
     * 주어진 행사 id 중 학술 프로그램인 것들 — 키는 event id, 값은 그 프로그램의 식별자와
     * 유형(acdm_actv_type의 코드·이름). 학술 프로그램이 아닌 행사는 키가 없다. 빈 입력에는
     * 빈 Map이며, 구현이 질의를 아예 보내지 않아도 된다.
     */
    Map<Long, AcademicProgramRef> academicProgramsAmong(Collection<Long> eventIds);
}
