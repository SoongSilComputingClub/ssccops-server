package org.sscc.ssccopsserver.domain.member.dto;

import java.util.List;

/*
 * 회원 삭제 미리보기 (GET /v1/members/{memberId}/deletion-preview, #361 · ADR-0021).
 *
 * 하드 삭제는 되돌릴 수 없으므로 확인 창이 «무엇이 지워지고 무엇이 막는가»를 숫자와 문구로
 * 보여준다 — 회원명을 직접 입력하게 하는 것과 함께 ADR-0021이 정한 확인 절차의 재료다.
 *
 * - responseCount · participationCount · historyCount — 함께 지워질 본인 데이터의 건수. 이력은
 *   등급·상태·정보 변경 셋을 합한 값이다(화면이 셋을 따로 그릴 이유가 없다). 역할 배정·하위 업무
 *   승인/투표/반려도 함께 지워지지만 싣지 않는다 — 지우려는 대상(연동 실패로 생긴 새 계정)에는
 *   그것이 없고, 필드를 늘리는 것은 실제로 보여줄 화면이 생길 때다.
 * - blockedBy — 삭제를 막을 참조의 사람 표기(«폼 작성자» …). 비어 있으면 지울 수 있다.
 *   MemberReferenceConstraints의 표기 그대로이며 실제 삭제가 409로 막힐 때의 메시지와 같은 문구다.
 *   순서는 그 표의 순서이고 중복은 없다.
 *
 * **미리보기는 약속이 아니다** — 이 응답과 삭제 사이에 다른 운영진이 그 회원으로 폼을 만들 수
 * 있다. 그때는 삭제가 409로 답하며, 그래서 삭제 쪽도 이 표를 다시 쓴다.
 */
public record MemberDeletionPreviewResponse(
        long responseCount, long participationCount, long historyCount, List<String> blockedBy) {}
