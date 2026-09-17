package org.sscc.ssccopsserver.domain.assistant.dto;

import java.util.List;

/*
 * 장 하나 — 본칙의 `## 제2장 회원`과 `## 부칙` (#397).
 *
 * ── 부칙을 별도의 축으로 두는 이유 ────────────────────────────
 *
 * **부칙에서 조번호가 1로 리셋된다.** 본칙 제1조(명칭)와 부칙 제1조(용어)가 둘 다 있어
 * `[제1조]` 하나로는 어느 쪽인지 알 수 없다 — 옛 회칙에도 있던 결함인데 인용을 실제로 만들어
 * 보기 전까지 드러나지 않았다. 그래서 `supplementary`가 인용 키의 일부이고 표기는
 * `부칙 제3조`로 굳는다.
 *
 * `title`은 «제2장 회원» 혹은 «부칙»이며 개정 마커는 떼어 낸 값이다. 이 문자열이 그대로 청크의
 * 임베딩 텍스트 앞에 붙는다 — «제28조 (설치)»가 무엇의 설치인지는 장이 말하기 때문이다.
 */
public record RegulationChapter(
        String title,
        boolean supplementary,
        String revisionMarker,
        int line,
        List<RegulationArticle> articles) {

    public RegulationChapter {
        articles = List.copyOf(articles);
    }
}
