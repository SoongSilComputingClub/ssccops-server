package org.sscc.ssccopsserver.domain.assistant.dto;

import java.util.List;

/*
 * 조 하나 — 청킹의 단위다 (#397 · 기획안 §5.3).
 *
 * ── 조 식별자가 두 값인 이유 ──────────────────────────────────
 *
 * **`제27조의2`가 있다.** 조번호를 `int` 하나로 두면 «27의2»를 파싱하다 죽거나 27로 읽혀
 * 제27조와 같은 조가 된다 — 앞의 것은 부팅 때 드러나지만 뒤의 것은 «세부 규정»을 물었는데
 * 개인정보 조항이 섞여 나오는 식으로만 드러난다. 그래서 `(number, branchNumber)` 두 값이며
 * **표기(`label`)는 원문 그대로 보존한다** — 인용에 다시 조립해 넣으면 `제27조의02` 같은 것이
 * 만들어질 자리가 생긴다.
 *
 * `preamble`은 항이 시작하기 전의 조 본문이다(«회원의 구분은 아래와 같이 한다.»). 항이 아예
 * 없는 조(제1조·제19조 등 열넷)는 이것이 본문의 전부다.
 */
public record RegulationArticle(
        int number,
        Integer branchNumber,
        String label,
        String title,
        String revisionMarker,
        int line,
        List<String> preamble,
        List<RegulationClause> clauses) {

    public RegulationArticle {
        preamble = List.copyOf(preamble);
        clauses = List.copyOf(clauses);
    }

    /** `제7조 (회원의 구분)` — 제목이 없으면 `제19조`. 청크 헤더와 인용이 함께 쓰는 표기다 */
    public String heading() {
        return title == null ? label : label + " (" + title + ")";
    }
}
