package org.sscc.ssccopsserver.domain.assistant.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.assistant.dto.RegulationArticle;
import org.sscc.ssccopsserver.domain.assistant.dto.RegulationChapter;
import org.sscc.ssccopsserver.domain.assistant.dto.RegulationChunk;
import org.sscc.ssccopsserver.domain.assistant.dto.RegulationClause;
import org.sscc.ssccopsserver.domain.assistant.dto.RegulationDocument;

/*
 * 장·조 트리를 적재 단위로 편다 — **조 1개 = 청크 1개** (#397 · 기획안 §5.3).
 *
 * ── 왜 고정 길이로 자르지 않는가 ──────────────────────────────
 *
 * 회칙은 사람의 자격을 판단하는 근거라 `[제7조 6항]`이 맞아야 하는데, **그 정확도는 조 단위
 * 청킹에서만 나온다.** 고정 길이로 자르면 인용이 조 경계를 넘어 «제7조의 근거»라며 제8조의
 * 문장을 보여 주는 일이 생기고, 그 오류는 답변이 그럴듯하기 때문에 아무도 찾지 못한다.
 *
 * ── 긴 조만 항 단위로 갈린다 ─────────────────────────────────
 *
 * 임베딩 모델의 입력 상한(8,192 토큰)에 닿아서가 아니다 — 한 청크가 열 항을 담으면 검색이
 * «그 조 어딘가»까지만 좁혀 주고 발췌가 조 전체가 되어 인용이 쓸모를 잃는다. 갈린 묶음마다
 * **조 헤더를 반복하는 것**이 그 대가를 갚는 자리다: 둘째 묶음만 검색에 걸려도 어느 조의
 * 몇 항인지 말할 수 있다.
 */
@Component
public class RegulationChunker {

    /*
     * 한 청크가 넘지 않으려는 글자 수 — 헤더까지 포함한 `text()` 길이다.
     *
     * **실측으로 고른 값이다.** 개정안 전문에서 이 값이면 조 36개가 청크 40개가 되고, 갈리는 것은
     * 제7·8·16·17조 넷이다(기획안 §5.3의 «청크 약 40개»가 그 수다). 더 키우면 제16·17조가 한
     * 덩어리로 남아 열 항짜리 발췌가 생기고, 더 줄이면 서너 항짜리 조까지 갈려 «1항만 담긴 청크»가
     * 늘어난다. 이 값은 **상한이 아니라 목표**다 — 항 하나가 혼자 이보다 길면 그대로 둔다.
     */
    static final int TARGET_CHUNK_CHARS = 450;

    public List<RegulationChunk> chunk(RegulationDocument document) {
        List<RegulationChunk> chunks = new ArrayList<>();
        for (RegulationChapter chapter : document.chapters()) {
            for (RegulationArticle article : chapter.articles()) {
                for (String body : bodies(chapter, article)) {
                    chunks.add(
                            new RegulationChunk(
                                    chunks.size(),
                                    chapter.title(),
                                    chapter.supplementary(),
                                    article,
                                    body));
                }
            }
        }
        return chunks;
    }

    /*
     * 조 하나가 몇 개의 본문이 되는가.
     *
     * 항이 없는 조(제1조·제19조 등)는 언제나 하나다 — 쪼갤 경계가 없고, 있다 해도 문장 중간을
     * 끊는 것은 조 단위 청킹을 버리는 것이다. **부칙 제5조(개정 이력 표)가 목표 길이를 넘는데도
     * 한 청크인 이유가 그것이다.**
     */
    private static List<String> bodies(RegulationChapter chapter, RegulationArticle article) {
        List<String> blocks = new ArrayList<>();
        if (!article.preamble().isEmpty()) {
            blocks.add(String.join("\n", article.preamble()));
        }
        for (RegulationClause clause : article.clauses()) {
            blocks.add(clause.text());
        }

        int headerLength = heading(chapter, article).length() + 1;
        String whole = String.join("\n", blocks);
        if (article.clauses().isEmpty() || headerLength + whole.length() <= TARGET_CHUNK_CHARS) {
            return List.of(whole);
        }

        List<String> bodies = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String block : blocks) {
            if (!current.isEmpty()
                    && headerLength + current.length() + 1 + block.length() > TARGET_CHUNK_CHARS) {
                bodies.add(current.toString());
                current = new StringBuilder(block);
                continue;
            }
            if (!current.isEmpty()) {
                current.append('\n');
            }
            current.append(block);
        }
        bodies.add(current.toString());
        return bodies;
    }

    /** 길이 계산에 쓰는 헤더 — 실제 조립은 `RegulationChunk.heading()`이 하고 규칙은 그쪽에 있다 */
    private static String heading(RegulationChapter chapter, RegulationArticle article) {
        return new RegulationChunk(0, chapter.title(), chapter.supplementary(), article, "")
                .heading();
    }
}
