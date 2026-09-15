package org.sscc.ssccopsserver.domain.assistant.dto;

import java.util.List;

/*
 * 추천 질문 (#403 · 기획안 §10 · §13.3).
 *
 * **서버가 내린다.** 코퍼스가 이제 화면에서 바뀌므로(ADR-0029) 웹에 하드코딩하면 업로드
 * 다음 날부터 거짓말을 한다 — 학칙이 빠지고 세칙이 들어와도 문구는 그대로 남는다.
 *
 * **빈 배열이 정상이다.** 코퍼스가 비어 있는 것이 새 환경의 기본 상태이고(§12.5), 그 상태에서
 * 답할 수 없는 질문을 권하지 않는다. 화면은 그때 고지 문구만 그린다(§13.1).
 *
 * 문자열 목록을 그대로 내리지 않고 record로 감싸는 것은 나중에 «이 질문이 어느 문서에서
 * 왔는가»를 함께 내릴 자리를 남겨 두기 위해서다 — 배열을 봉투 없이 내리면 그때 계약이 깨진다.
 */
public record AssistantSuggestionsResponse(List<String> questions) {

    public AssistantSuggestionsResponse {
        questions = List.copyOf(questions);
    }
}
