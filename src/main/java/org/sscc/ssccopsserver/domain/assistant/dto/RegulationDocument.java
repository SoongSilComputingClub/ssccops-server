package org.sscc.ssccopsserver.domain.assistant.dto;

import java.util.List;

/*
 * `RegulationParser`가 돌려주는 장·조 트리 (#397).
 *
 * **청크가 아니다.** 트리는 원문의 구조를 그대로 담고, 그것을 조 단위 청크로 펴는 것은
 * `RegulationChunker`의 몫이다 — 둘을 한 클래스에 두면 «한 조가 길면 항 단위로 쪼갠다»의
 * 기준을 바꿀 때 파싱 규칙까지 함께 읽어야 한다.
 */
public record RegulationDocument(List<RegulationChapter> chapters) {

    public RegulationDocument {
        chapters = List.copyOf(chapters);
    }

    /** 장을 가로질러 조를 순서대로 — 개수를 세거나 전수 검사할 때 쓴다 */
    public List<RegulationArticle> articles() {
        return chapters.stream().flatMap(chapter -> chapter.articles().stream()).toList();
    }
}
