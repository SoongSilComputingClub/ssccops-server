package org.sscc.ssccopsserver.domain.assistant.dto;

import java.util.List;

import org.sscc.ssccopsserver.domain.assistant.code.AssistantCorpusState;

/*
 * 추천 질문과 **코퍼스 상태** (#403 · #449 · 기획안 §10 · §13.1 · §13.3).
 *
 * **서버가 내린다.** 코퍼스가 이제 화면에서 바뀌므로(ADR-0029) 웹에 하드코딩하면 업로드
 * 다음 날부터 거짓말을 한다 — 학칙이 빠지고 세칙이 들어와도 문구는 그대로 남는다.
 *
 * **빈 배열이 정상이다.** 코퍼스가 비어 있는 것이 새 환경의 기본 상태이고(§12.5), 그 상태에서
 * 답할 수 없는 질문을 권하지 않는다. 화면은 그때 고지 문구만 그린다(§13.1).
 *
 * **`corpusState`가 그 고지 문구를 가른다**(#449). «문서가 없다»와 «문서는 있는데 시행 중인 것이
 * 없다»가 둘 다 빈 배열이었고, 화면은 후자에게도 «문서를 올려주세요»를 말했다 — 업로드가 언제나
 * `DRAFT`로 들어오므로(ADR-0034) 그것이 **첫 업로드마다 반드시 지나는 화면**이다. 값의 뜻은
 * `AssistantCorpusState` 한 곳에 있다.
 *
 * **문자열 목록을 그대로 내리지 않고 record로 감싼 것이 여기서 값을 했다** — 필드를 더하는 것이
 * 이 봉투가 존재하는 이유이고, 배열을 봉투 없이 내렸다면 그때 계약이 깨졌다. 같은 이유로
 * «이 질문이 어느 문서에서 왔는가»도 여기 들어올 자리가 남아 있다.
 */
public record AssistantSuggestionsResponse(
        AssistantCorpusState corpusState, List<String> questions) {

    public AssistantSuggestionsResponse {
        questions = List.copyOf(questions);
    }
}
