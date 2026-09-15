package org.sscc.ssccopsserver.domain.assistant.service;

import org.sscc.ssccopsserver.domain.assistant.dto.AssistantQueryRequest;
import org.sscc.ssccopsserver.domain.assistant.dto.AssistantQueryResponse;
import org.sscc.ssccopsserver.domain.assistant.dto.AssistantSuggestionsResponse;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/**
 * 규정 도우미에게 묻는다 (#403 · #406 · 기획안 §6 · §7 · §10).
 *
 * <p><b>코퍼스를 바꾸는 조작({@code RagDocumentService})과 나뉜다</b> — 인가 요구가 다르고(질의는 인증만 · 코퍼스는 {@code
 * RAG_DOCUMENT_MANAGE}), 그 차이가 컨트롤러 둘로 이어진다. 한 서비스에 두면 클래스 레벨 인가를 걸 수 없어 핸들러마다 붙이게 되고 하나 빠뜨리는 순간
 * 코퍼스가 열린다.
 */
public interface AssistantService {

    /**
     * 질문 하나에 답한다 — <b>근거가 없으면 모델을 부르지 않는다</b>(§6.1).
     *
     * <p>앞선 턴이 있으면 그것을 맥락으로 넣는다(#406). <b>검색어는 언제나 이번 질문 하나이고</b> 이력은 생성에만 쓰인다 — 이유는 {@code
     * AssistantConversations}에 있다.
     *
     * @param member 인증 주체. <b>프롬프트에는 들어가지 않는다</b>(§11 — 무료 티어 입력은 제품 개선에 쓰일 수 있다). 로그의 «누가 물었나»와
     *     회원별 한도({@code AssistantRateLimiter} · #404)가 세는 축이며, <b>대화 식별자의 앞부분</b>이기도 하다(§7.4)
     */
    AssistantQueryResponse query(AssistantQueryRequest request, MemberEntity member);

    /**
     * 대화를 지운다 — 패널의 {@code ↺}(§13.1 · ssccops-web#434).
     *
     * <p><b>없는 대화를 지우는 것도 성공이다</b>(만료됐거나 아직 묻지 않은 식별자를 구별해 줄 값이 서버에 없다). 대신 <b>남의 것이면 403</b>이다 —
     * 지우는 것도 남의 대화에 닿는 일이라 읽기와 같은 규칙을 쓴다.
     */
    void clearConversation(String conversationId, MemberEntity member);

    /** 지금 코퍼스가 답할 수 있는 추천 질문 (§13.3). <b>코퍼스가 비면 빈 목록이며 그것이 정상이다</b> */
    AssistantSuggestionsResponse suggestions();
}
