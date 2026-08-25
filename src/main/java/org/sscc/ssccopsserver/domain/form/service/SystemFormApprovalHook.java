package org.sscc.ssccopsserver.domain.form.service;

import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;

/*
 * 시스템 폼 응답이 승인됐을 때 실행되는 후속 처리의 훅 (#150).
 *
 * ── 왜 인터페이스인가 ──────────────────────────────────────────
 * 기획안(PROPOSAL) 응답이 ACCEPT되면 학술 활동(Event + AcademicProgram + CurriculumItem)이
 * 생겨야 한다. 폼 도메인이 학술 도메인의 서비스를 직접 주입받아 부르는 쪽이 짧지만, 그렇게 하면
 * **폼이 학술을 알게 된다** — 폼은 지원서·설문·모집 신청서를 함께 다루는 일반 도메인이고
 * 학술은 그 위에 얹힌 한 가지 쓰임일 뿐인데, 방향이 거꾸로 서면 시스템 폼이 하나 늘 때마다
 * FormResponseServiceImpl에 도메인 이름이 하나씩 박힌다(회원 도메인이 운영 도메인의
 * SubWorkService.countOngoingByOwner 하나만 아는 것과 같은 규칙, AR-07).
 *
 * 그래서 폼은 "sys_form_cd가 이것인 응답이 승인됐다"는 사실만 알리고, 그 사실에 반응하는 쪽이
 * 자기 도메인에서 구현체를 빈으로 등록한다. 폼은 누가 구현하는지 모른다 —
 * SystemFormApprovalHooks가 sys_form_cd로 찾아 줄 뿐이다.
 *
 * 스프링 이벤트(ApplicationEventPublisher)도 후보였지만 쓰지 않았다. **승인과 이관은 한
 * 트랜잭션이어야 하고**(ssccops#148 BR — 이관이 실패하면 ACCEPT 자체가 롤백된다), 이벤트는
 * 리스너의 예외가 발행자에게 도달하는지가 동기/비동기·트랜잭션 단계 설정에 달려 있어 그
 * 원자성이 설정 한 줄로 조용히 깨진다. 인터페이스 호출은 그냥 같은 트랜잭션 안의 메서드 호출이다.
 *
 * ── 이 인터페이스가 왜 #150 소관인가 ──────────────────────────
 * #150 본문은 "#141이 인터페이스를 정의하고 이 이슈가 구현체만 등록하는 쪽이 자연스럽다"고
 * 적었지만 #141은 이미 머지됐고 그 자리를 만들지 않았다 — 구현체 없는 인터페이스만 먼저 두면
 * 무엇을 위한 모양인지 알 수 없어 추측으로 굳는다. 그래서 인터페이스와 첫 구현체(기획안)를
 * 함께 이 이슈가 만든다.
 */
public interface SystemFormApprovalHook {

    /*
     * 이 훅이 반응하는 시스템 폼 코드(form.sys_form_cd). form_id도 제목도 아닌 것은 #140이
     * 세운 규칙 그대로다 — form_id는 IDENTITY라 환경마다 다르고 제목·라벨은 화면에서 바뀐다.
     */
    String sysFormCd();

    /*
     * 승인 직후 호출된다. **호출부(FormResponseReviewServiceImpl 역할을 하는
     * FormResponseServiceImpl.reviewResponse)의 트랜잭션 안에서 실행되므로 여기서 던진 예외는
     * ACCEPT 자체를 롤백시킨다.** 구현체가 조용히 삼키면 "승인은 됐는데 아무것도 만들어지지
     * 않은" 상태가 남으므로 삼키지 말 것.
     *
     * 인자로 응답 엔티티를 통째로 받는 것은 구현체마다 읽어야 하는 것이 다르기 때문이다 —
     * 기획안은 응답 내용(rspns_cn)과 응답자(mbr)를 둘 다 쓴다.
     */
    void onAccepted(FormResponseHistoryEntity response);

    /*
     * 검토 화면이 승인 버튼을 누르기 전에 보여줄 파생 정보 (#150 · 응답 상세의
     * academicProgramPreview). 반환 타입이 Object인 것은 폼 도메인이 그 모양을 알 수 없기
     * 때문이며, 실제 형태는 구현체가 자기 도메인의 DTO로 정한다(Jackson이 그대로 직렬화한다).
     *
     * **onAccepted와 같은 파서를 써야 한다.** 미리보기와 실제 이관이 다른 파싱 결과를 내면
     * 검토자는 화면에서 본 것과 다른 것을 승인하게 된다(ssccops#148 BR — 파싱은 한 곳에만 둔다).
     *
     * **여기서 예외를 던지지 말 것.** 파싱에 실패한 응답이야말로 검토자가 사유를 봐야 하는
     * 응답인데, 상세 조회가 통째로 500이 되면 그 화면을 열 수조차 없다 — 실패는 던지는 대신
     * 반환값에 실어 보낸다. 훅을 등록하지 않은 코드를 위해 기본 구현은 null이다.
     */
    default Object preview(FormResponseHistoryEntity response) {
        return null;
    }
}
