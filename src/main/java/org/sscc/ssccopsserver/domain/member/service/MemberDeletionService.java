package org.sscc.ssccopsserver.domain.member.service;

import org.sscc.ssccopsserver.domain.member.dto.MemberDeletionPreviewResponse;

/*
 * 회원 하드 삭제 — **임시** 기능이다 (#361 · ADR-0021).
 *
 * 연동 실패(#264 이전 «기존 회원 정보와 연결하기»가 안 보이던 동안)로 명부 행 R과 새 계정 N이
 * 같은 사람으로 둘이 된 것을 정리하려고 연다. N을 지우면 그 사람이 다시 로그인했을 때 «가입
 * 필요»가 되어 연결 화면으로 가고, 그것이 복구 경로다. 구글 계정은 지우지 않는다.
 *
 * ── 왜 MemberService가 아니라 별도 빈인가 ──────────────────────
 * 이 기능은 플래그로 닫히고 폐기 조건이 정해져 있다(ADR-0021 — 중복 정리가 끝나고 1주간 새
 * 중복이 없으면 플래그를 끈다. 코드는 남긴다). 회원을 «만들고 읽는» MemberService에 «없애는»
 * 메서드를 섞으면, 닫힌 뒤에도 그 클래스를 읽는 사람마다 이 경로가 살아 있는지 확인해야 한다.
 * 한 빈에 모여 있으면 플래그·오류 코드·번역 표가 어디 있는지 한눈에 보인다.
 *
 * ── 경계는 DB가 강제한다 ───────────────────────────────────────
 * mbr을 가리키는 FK 29개 중 본인 데이터 9개(와 딸린 3개)는 V9가 ON DELETE CASCADE로 바꿨고,
 * 행위자 참조 20개는 NO ACTION 그대로다. 그래서 삭제는 deleteById 한 줄이고, 남의 기록을
 * 가리키는 회원은 DB가 막는다 — 코드로 20개 도메인을 조회해 막으면 도메인 순환이 걸리고 하나
 * 빠뜨리면 500이다(ADR-0021 A안). 이 서비스가 하는 일은 그 실패를 409로 번역하는 것뿐이다.
 */
public interface MemberDeletionService {

    /*
     * 지워질 것과 막을 것을 미리 본다. 플래그가 꺼져 있으면 404 FEATURE_DISABLED, 없는 회원은
     * 404 NOT_FOUND. 본인 여부는 보지 않는다 — 미리보기는 아무것도 바꾸지 않는다.
     */
    MemberDeletionPreviewResponse preview(Long memberId);

    /*
     * 회원을 지운다. 한 트랜잭션이며 cascade는 DB의 몫이다.
     *
     * 플래그 off → 404 FEATURE_DISABLED · 없는 회원 → 404 NOT_FOUND · 본인 → 400 CANNOT_DELETE_SELF ·
     * 행위자 참조가 남아 있음 → 409 MEMBER_REFERENCED(메시지에 어느 참조인지).
     */
    void delete(Long memberId, Long requesterId);
}
