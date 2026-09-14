package org.sscc.ssccopsserver.domain.assistant.dto;

import java.util.List;

/*
 * 조 아래의 항 하나 (#397).
 *
 * **호·절은 별도의 노드가 아니라 이 항의 줄이다.** 들여쓴 목록(`- **1호** …` · `- **1절** …`)도,
 * 번호 없이 들여쓴 단서 문장도 전부 `lines`에 이어 붙는다 — 청킹의 단위가 항이므로 그 아래를
 * 더 쪼갤 자리가 없고, 나누면 «1호만 담긴 청크»가 생겨 인용이 무엇의 1호인지 말하지 못한다.
 *
 * `lines`는 이미 정규화된 텍스트다 — 마크다운 목록 기호와 강조(`**`)를 벗기고 개정 마커를
 * 떼어 냈으며, 호·절은 두 칸 들여쓰기로 계층만 남긴다.
 */
public record RegulationClause(int number, String revisionMarker, int line, List<String> lines) {

    public RegulationClause {
        lines = List.copyOf(lines);
    }

    /** 임베딩 텍스트에 실리는 모양 — `1항 …` 다음 줄에 ` 1호 …` */
    public String text() {
        return String.join("\n", lines);
    }
}
