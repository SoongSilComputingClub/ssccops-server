package org.sscc.ssccopsserver.domain.assistant.dto;

import java.time.LocalDate;
import java.util.List;

import org.sscc.ssccopsserver.domain.assistant.code.RagApplyStatus;

/*
 * 질의 한 건의 답 (#403 · #406 · 기획안 §6.3 · §7.4).
 *
 * ══ `answered`가 이 기능의 가장 중요한 필드다 ═══════════════════
 *
 * `false`면 **모델을 부르지 않았거나 그 답을 버렸다**는 뜻이고, `answer`는 정해진 안내 문구,
 * `citations`는 **빈 배열(null 아님)**이다. 규정 답변에서 없는 조항을 지어내는 것은 틀린 답보다
 * 나쁘다 — 운영진이 그것을 근거로 사람의 자격을 판단한다(§6.1).
 *
 * 화면이 «참고용» 배지를 달고 약한 답을 내보내는 길은 기각했다. 배지는 읽히지 않고 문장은
 * 읽힌다.
 *
 * ══ 판본 배지의 재료 ════════════════════════════════════════════
 *
 * `applyStatus`·`effectiveDate`는 답변 위의 «2026-03-24 시행 회칙 기준» 배지가 쓰는 값이다
 * (§13.1). **거절(`answered=false`)일 때는 둘 다 `null`이다** — 기댄 판본이 없다.
 *
 * 인용이 여러 문서에 걸치면 **첫 인용의 판본**을 싣는다. 배지가 답하는 물음이 «어느 판본을
 * 기준으로 읽었나»인데 날짜 여럿을 배지 하나에 담을 방법이 없고, 첫 인용이 답변이 가장 크게
 * 기댄 근거다(유사도 순서 그대로다).
 *
 * `DRAFT`는 시행일이 없으므로 `effectiveDate`가 `null`이다 — 그때 화면은 **«의결 전 개정안
 * 기준»**을 그린다. Phase 1의 검색 조건이 `EFFECTIVE`뿐이라(§5.5) 지금 이 값은 언제나
 * `EFFECTIVE`지만, **화면 계약을 여기서 좁히지 않는다**: 좁히면 개정안을 상대로 묻는 길이
 * 열릴 때 두 답이 화면에서 같아 보인다.
 *
 * ══ `conversationId`는 언제나 실린다 ═══════════════════════════
 *
 * **거절일 때도 싣는다.** 화면은 이 값을 다음 질문에 그대로 실어 대화를 잇고(§7.4), 근거를 찾지
 * 못한 첫 질문 뒤에 다시 묻는 것이 이 기능의 흔한 사용이다. 요청이 비워 보냈으면 **서버가 방금
 * 발급한 값**이고, 실어 보냈으면 그 값 그대로다 — 클라이언트가 만드는 값이 아니다.
 *
 * 값이 있다고 이력이 있다는 뜻은 아니다. 24시간 슬라이딩 만료(§7.2)가 지난 대화는 빈 이력으로
 * 이어지며 **화면에는 새 대화처럼 보인다** — 오류가 아니다(ssccops-web#434).
 *
 * ══ 싣지 않는 것 ═══════════════════════════════════════════════
 *
 * 질문 원문을 되돌려주지 않는다(§11 — 질문·답변을 어디에도 남기지 않는다는 규칙의 같은 줄기다.
 * 화면은 자기가 보낸 것을 이미 알고 있다). 앞선 턴들도 내리지 않는다 — 이력은 **모델에게 줄
 * 맥락**이지 화면이 그릴 재료가 아니고(말풍선은 화면이 이미 들고 있다), 내리기 시작하면 24시간
 * 뒤에 사라지는 값이 화면의 정본이 된다.
 */
public record AssistantQueryResponse(
        String answer,
        List<AssistantCitationResponse> citations,
        RagApplyStatus applyStatus,
        LocalDate effectiveDate,
        boolean answered,
        String conversationId) {

    public AssistantQueryResponse {
        citations = List.copyOf(citations);
    }

    /**
     * 근거를 찾지 못했다 — <b>모델을 부르지 않았거나 그 답을 버렸다</b>.
     *
     * <p>{@code citations}가 빈 배열이고 판본 값 둘이 {@code null}인 것이 계약이다(클래스 주석).
     *
     * <p><b>{@code conversationId}는 그대로 싣는다</b> — 거절도 대화의 한 턴이고, 화면은 그 값으로 이어 묻는다.
     */
    public static AssistantQueryResponse unanswered(String guidance, String conversationId) {
        return new AssistantQueryResponse(guidance, List.of(), null, null, false, conversationId);
    }
}
