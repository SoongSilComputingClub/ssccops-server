package org.sscc.ssccopsserver.domain.operation.dto;

import jakarta.validation.constraints.NotNull;

import org.sscc.ssccopsserver.domain.operation.entity.VoteChoice;

/*
 * 정족수 승인 투표 요청 (OPS-015 · POST /v1/sub-works/{subWorkId}/approvals/votes).
 *
 * 투표자는 요청 본문이 아니라 인증 주체에서 온다 (LY-05). 회차도 받지 않는다 —
 * 어느 회차의 투표인지는 서버가 상태 이력에서 파생한다.
 */
public record SubWorkVoteRequest(@NotNull VoteChoice vote) {}
