package org.sscc.ssccopsserver.domain.assistant.service;

import org.sscc.ssccopsserver.domain.assistant.dto.AssistantQueryRequest;
import org.sscc.ssccopsserver.domain.assistant.dto.AssistantQueryResponse;
import org.sscc.ssccopsserver.domain.assistant.dto.AssistantSuggestionsResponse;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/**
 * 규정 도우미에게 묻는다 (#403 · 기획안 §6 · §10).
 *
 * <p><b>코퍼스를 바꾸는 조작({@code RagDocumentService})과 나뉜다</b> — 인가 요구가 다르고(질의는 인증만 · 코퍼스는 {@code
 * RAG_DOCUMENT_MANAGE}), 그 차이가 컨트롤러 둘로 이어진다. 한 서비스에 두면 클래스 레벨 인가를 걸 수 없어 핸들러마다 붙이게 되고 하나 빠뜨리는 순간
 * 코퍼스가 열린다.
 */
public interface AssistantService {

    /**
     * 질문 하나에 답한다 — <b>근거가 없으면 모델을 부르지 않는다</b>(§6.1).
     *
     * @param member 인증 주체. <b>프롬프트에는 들어가지 않는다</b>(§11 — 무료 티어 입력은 제품 개선에 쓰일 수 있다). 로그의 «누가 물었나»와
     *     앞으로 들어올 회원별 한도(#404)의 재료다
     */
    AssistantQueryResponse query(AssistantQueryRequest request, MemberEntity member);

    /** 지금 코퍼스가 답할 수 있는 추천 질문 (§13.3). <b>코퍼스가 비면 빈 목록이며 그것이 정상이다</b> */
    AssistantSuggestionsResponse suggestions();
}
