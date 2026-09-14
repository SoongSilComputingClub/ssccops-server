package org.sscc.ssccopsserver.domain.assistant.dto;

import java.time.Instant;
import java.util.List;

/*
 * 상세 (#401 · `GET /v1/assistant/documents/{id}` · 기획안 §10).
 *
 * **목록 한 행을 그대로 품는다.** 필드를 펼쳐 적으면 `RagDocumentResponse`와 같은 값이 두 벌이
 * 되고, 한쪽에 필드가 늘 때 다른 쪽이 따라오지 않는다 — 그 갈림이 화면에서는 «목록에는 있는데
 * 상세에는 없는 값»으로 나타난다.
 *
 * 상세만 갖는 것은 셋이다.
 *
 * <ul>
 *   <li><b>원본 다운로드 URL</b> — 서명은 `FilePresigner`가 만든다(#220). 「누가 볼 수 있는가」는
 *       이 도메인이 클래스 레벨 `@RequireAuthority(RAG_DOCUMENT_MANAGE)`로 이미 끝냈고, 서명하는
 *       쪽은 그것을 다시 묻지 않는다. **남은 시간을 함께 싣는 것**은 열어 둔 화면이 만료 전에
 *       상세를 다시 부를 수 있게 하기 위해서다(행사 이미지·인증사진과 같은 모양).
 *   <li><b>조 목록</b> — `STRUCTURED`에만 있고 `GENERIC`에서는 빈 목록이다. 원본을 다시 파싱해
 *       만든다(서비스 주석).
 *   <li><b>색인 시각 둘</b>과 등록자 — 목록의 한 행에는 넣지 않는 값이다. 표가 이미 여섯 칸이고
 *       (§13.2) 여기서 «누가 언제 올렸고 색인이 얼마나 걸렸나»를 본다.
 * </ul>
 *
 * 실패 사유는 상세만의 값이 아니다 — 목록 행(`RagDocumentResponse.failureReason`)이 이미 갖는다.
 * 화면이 실패 배지의 툴팁에 그것을 쓰기 때문이며, 상세에도 같은 값이 따라온다.
 */
public record RagDocumentDetailResponse(
        RagDocumentResponse document,
        Long registrantId,
        String registrantName,
        Instant indexStartedAt,
        Instant indexEndedAt,
        Instant updatedAt,
        String downloadUrl,
        Long downloadUrlExpiresInSeconds,
        List<RagDocumentArticleResponse> articles) {

    public RagDocumentDetailResponse {
        articles = List.copyOf(articles);
    }
}
