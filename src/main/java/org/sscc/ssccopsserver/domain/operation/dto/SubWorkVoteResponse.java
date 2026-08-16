package org.sscc.ssccopsserver.domain.operation.dto;

import org.sscc.ssccopsserver.domain.operation.entity.VoteChoice;

/*
 * 정족수 승인 투표 응답 (OPS-015 · VoteResult).
 *
 * met이 true여도 업무 상태·승인 상태는 그대로다 — 정족수는 승인자를 대체하지 않는다.
 * 찬성이 다 모여도 승인자가 최종 승인(TR-03)을 눌러야 완료된다 (POL-007 O-03 확정).
 *
 * myVote·approvalSequence는 정의서에 없던 필드다. 승인함 카드가 내 표를 다시 조회하지 않고
 * 표시할 수 있어야 하고, 회차는 반려 후 재상정으로 집계가 초기화됐다는 사실을 화면이 알 수
 * 있는 유일한 값이다.
 */
public record SubWorkVoteResponse(
        Long subWorkId,
        VoteChoice myVote,
        boolean met,
        long currentCount,
        int requiredCount,
        int approvalSequence) {}
