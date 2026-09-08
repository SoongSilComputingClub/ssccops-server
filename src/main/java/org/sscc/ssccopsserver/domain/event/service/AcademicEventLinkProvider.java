package org.sscc.ssccopsserver.domain.event.service;

import java.util.Collection;
import java.util.Set;

/*
 * 주어진 행사 중 학술 활동에서 이관된 것을 알려 주는 포트 (ssccops#242).
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
 * ── 왜 한 건씩이 아니라 묶음으로 묻는가 ────────────────────────
 * 공개 목록은 이미 읽어 온 행사 전부에 대해 이 판별이 필요하다. 한 건씩 물으면 목록 길이만큼
 * 질의가 늘어난다(N+1) — 그래서 인자가 컬렉션이고 구현은 IN 하나로 끝낸다.
 */
public interface AcademicEventLinkProvider {

    /*
     * 주어진 행사 id 중 학술 활동에 연결된 것들. 빈 입력에는 빈 집합이며, 구현이 질의를 아예
     * 보내지 않아도 된다.
     */
    Set<Long> academicEventIdsAmong(Collection<Long> eventIds);
}
