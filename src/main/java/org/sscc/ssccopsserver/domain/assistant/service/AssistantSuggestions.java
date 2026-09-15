package org.sscc.ssccopsserver.domain.assistant.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

/*
 * 추천 질문 — **코퍼스에 실제로 있는 문서에만 매인다** (#403 · 기획안 §13.3).
 *
 * ══ 왜 서버가 내리는가 ══════════════════════════════════════════
 *
 * 코퍼스가 이제 화면에서 바뀐다(ADR-0029). 웹에 세 질문을 하드코딩하면 **업로드 다음 날부터
 * 거짓말을 한다** — 지원금 지침이 빠져도 «지원금 한도는?»이 그대로 남고, 누르면 «찾지
 * 못했습니다»가 돌아온다. 그 화면은 도우미가 고장 난 것처럼 보인다.
 *
 * ══ 표가 문서 코드에 매여 있다 ══════════════════════════════════
 *
 * 후보마다 «어느 문서가 있어야 답할 수 있는가»를 `doc_cd`로 적어 두고, 지금 검색 대상인
 * 판본(`INDEXED && EFFECTIVE`)의 코드만 통과시킨다. **제목이 아니라 코드로 묻는 것**은
 * 운영진이 표시명을 다듬는 순간 추천 질문이 통째로 사라지지 않게 하기 위해서다.
 *
 * 문서 표시명으로 질문을 **지어내는** 안(«「{문서명}」에는 어떤 내용이 있나요?»)은 택하지
 * 않았다. 언제나 세 개를 채울 수 있다는 것이 장점인데, 조 단위 청크에는 문서명이 본문에 없어
 * (#397 — 헤더가 «제2장 회원 · 제7조»다) 그 질문이 임계값을 넘지 못하고 **추천 질문을 눌렀는데
 * 거절당하는** 화면이 된다. 지금 이 표가 비면 화면은 고지 문구만 그린다(§13.1) — 그것이
 * «코퍼스가 비어 있다»의 정직한 표현이다.
 *
 * ⚠️ **질문 문구는 골든셋(#405)이 확정한다.** 여기 적힌 셋은 기획안 §13.3이 «회칙이 스스로
 * 답할 수 있다»고 본 것이고, 실제 인용이 나오는지는 실제 코퍼스로 재 봐야 안다. 고칠 때
 * 웹 배포가 필요 없다는 것이 서버가 내리는 값의 값어치다.
 */
@Component
public class AssistantSuggestions {

    /** 화면이 그리는 칸 수(§13.1). 더 내려도 패널에 들어가지 않는다 */
    static final int MAX = 3;

    /*
     * 후보 표 — 순서가 곧 우선순위다. 회칙(`REGULATION`)이 앞인 것은 그것이 첫 업로드 대상이자
     * 다른 문서가 없어도 답할 수 있는 유일한 문서이기 때문이다.
     *
     * 어휘(`REGULATION`·`GUIDELINE`·`BYLAW`)는 **운영 규칙이고 코드가 강제하지 않는다**
     * (ssccops#325 — `doc_cd`는 표준코드 그룹이 아니라 문자열이다). 운영진이 다른 코드로 올리면
     * 그 줄이 조용히 꺼지는 것이 대가이며, 그래서 이 표는 «없으면 안 보인다»를 감수할 수 있는
     * 값(추천 질문)에만 쓴다 — 인가·검색 판정은 어느 것도 이 표를 보지 않는다.
     */
    private static final List<Candidate> CANDIDATES =
            List.of(
                    new Candidate("REGULATION", "정회원으로 승격하려면 어떤 조건을 갖춰야 하나요?"),
                    new Candidate("REGULATION", "회칙을 개정하려면 어떤 절차를 거치나요?"),
                    new Candidate("REGULATION", "임원이 임기 중에 그만두면 회원 등급은 어떻게 되나요?"),
                    new Candidate("GUIDELINE", "학술 활동 지원금의 한도와 정산 기한은 어떻게 되나요?"),
                    new Candidate("BYLAW", "스터디 출석률이 미달이면 어떻게 처리하나요?"));

    /**
     * 지금 답할 수 있는 질문 최대 {@value #MAX}개.
     *
     * @param documentCodes 검색 대상인 판본들의 {@code doc_cd} — 코퍼스가 비어 있으면 빈 목록이 나가고, 그것이 새 환경의 정상 상태다
     */
    public List<String> forDocumentCodes(Set<String> documentCodes) {
        List<String> questions = new ArrayList<>();
        for (Candidate candidate : CANDIDATES) {
            if (questions.size() == MAX) {
                break;
            }
            if (documentCodes.contains(candidate.documentCode())) {
                questions.add(candidate.question());
            }
        }
        return List.copyOf(questions);
    }

    /** 질문 하나와 «그 질문이 기대는 문서» */
    private record Candidate(String documentCode, String question) {}
}
