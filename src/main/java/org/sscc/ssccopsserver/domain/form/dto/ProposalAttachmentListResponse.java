package org.sscc.ssccopsserver.domain.form.dto;

import java.util.List;

/**
 * 기획안 첨부 목록 (server#685) — 발급 순(fileId 오름차순)과 사용량.
 *
 * <p><b>상한을 서버 값으로 싣는다</b>({@code maxTotalSize}·{@code maxCount}). 화면은 «12.3MB / 20MB»와 남은 개수를 이
 * 값으로 그린다 — 화면이 20MB·5개를 따로 박아 두면 서버 상수를 바꾸는 날 둘이 갈리고, 그 어긋남은 «화면은 된다는데 서버가 413»으로 드러난다. {@code
 * totalSize}는 남아 있는 행의 신고 크기 합이며 발급 판정이 세는 값과 같다.
 */
public record ProposalAttachmentListResponse(
        List<ProposalAttachmentResponse> attachments,
        long totalSize,
        long maxTotalSize,
        int maxCount) {}
