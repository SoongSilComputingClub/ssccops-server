package org.sscc.ssccopsserver.domain.assistant.service;

import java.util.Map;

import org.springframework.ai.document.Document;
import org.sscc.ssccopsserver.domain.assistant.code.CitationType;
import org.sscc.ssccopsserver.domain.assistant.code.RagDocumentType;

/*
 * 검색이 골라 온 청크 하나와 그것이 속한 판본 (#403).
 *
 * **모델에게 넣어 준 발췌의 목록이 곧 인용 검증의 대조표다** — 「모델이 단 인용이 실제로 넣어
 * 준 청크에 있는가」를 이 객체들에 물어본다(`CitationVerifier`). 그래서 검색 결과를 그대로
 * 들고 다니지 않고 판본(`SearchableDocument`)을 붙여 둔다: 인용 카드에는 문서명·판본이 필요한데
 * 그 값은 청크 메타에 없고(있어도 색인 시점의 값이다), 청크에서 판본으로 가는 길은
 * `metadata.ragDocId` 하나뿐이다.
 *
 * ── 메타데이터를 읽는 자리를 여기로 모은 이유 ─────────────────
 *
 * `vector_store.metadata`는 jsonb라 **타입이 왕복하며 바뀐다** — 우리가 `Integer`로 넣은 값이
 * Jackson을 지나 `Integer`로 올 수도, 다른 수 타입으로 올 수도 있다(스텁 저장소는 넣은 그대로
 * 돌려준다). 그 변환을 서비스와 검증기가 각자 하면 「로컬 테스트는 통과하는데 실제 PostgreSQL
 * 에서만 인용이 하나도 안 붙는」 고장이 난다. key 이름은 `RagChunkMetadata`가, 값을 읽는 방법은
 * 여기가 안다.
 */
public record RetrievedChunk(Document chunk, SearchableDocument source) {

    /** 임베딩에 들어간 본문 — 헤더 한 줄 + 조문/조각. 발췌 블록에 그대로 싣는다 */
    String text() {
        return chunk.getText() == null ? "" : chunk.getText();
    }

    /** 유사도. 저장소가 채우지 않았으면 0이다 — 임계값 재판정이 그때는 통과시킨다(스텁이 그렇다) */
    double score() {
        return chunk.getScore() == null ? 0d : chunk.getScore();
    }

    /**
     * 이 청크가 낳는 인용의 모양.
     *
     * <p>청크 메타의 {@code docType}을 먼저 보고, 없으면 판본의 유형을 쓴다 — 둘은 같은 값이지만(유형은 판본 안에서 바뀌지 않는다) 메타가 빠진 옛
     * 청크에도 답할 수 있어야 한다.
     */
    CitationType citationType() {
        return docType() == RagDocumentType.STRUCTURED ? CitationType.ARTICLE : CitationType.PAGE;
    }

    /*
     * 모르는 값이면 판본의 유형으로 내려간다 — 이 값은 우리 코드가 찍지만, 읽는 쪽이 사용자
     * 요청 경로라 「이상한 메타 하나가 질의를 500으로 만든다」를 두지 않는다. 유형은 판본 안에서
     * 바뀌지 않으므로 그 폴백이 정확하다.
     */
    private RagDocumentType docType() {
        String type = string(RagChunkMetadata.DOC_TYPE);
        if (type == null) {
            return source.type();
        }
        try {
            return RagDocumentType.valueOf(type);
        } catch (IllegalArgumentException ignored) {
            return source.type();
        }
    }

    /** `제2장 회원` · `부칙`. `GENERIC` 청크에는 없다 */
    String chapter() {
        return string(RagChunkMetadata.CHAPTER);
    }

    /** 부칙 여부 — <b>이것이 없으면 `제1조`가 두 곳을 가리킨다</b>(§5.3) */
    boolean supplementary() {
        return Boolean.parseBoolean(
                String.valueOf(chunk.getMetadata().get(RagChunkMetadata.SUPPLEMENTARY)));
    }

    /** 조번호 */
    Integer articleNumber() {
        return integer(RagChunkMetadata.ARTICLE_NUMBER);
    }

    /** 가지번호 — `제27조의2`의 2. 없으면 null이고, <b>그때 `제27조`와 별개의 조다</b> */
    Integer articleBranchNumber() {
        return integer(RagChunkMetadata.ARTICLE_BRANCH_NUMBER);
    }

    /** 인용 표기 — `제7조 (회원의 구분)` · `부칙 제3조 (의결의 순서)`. `GENERIC`에는 없다 */
    String articleCitation() {
        return string(RagChunkMetadata.CITATION);
    }

    /** 원문 표기 그대로의 조 번호 — `제27조의2`. `GENERIC`에는 없다 */
    String articleLabel() {
        return string(RagChunkMetadata.ARTICLE_LABEL);
    }

    /**
     * <b>모델에게 «이 발췌를 가리키려면 이렇게 쓰라»고 일러 주는 표기</b> — `제7조` · `부칙 제3조` · `p.12` · 문서명.
     *
     * <p>세 모양뿐인 것이 검증을 가능하게 한다({@code CitationVerifier}) — 모델이 표기를 자유롭게 지으면 「실제 청크에 있는가」를 대조할 대상이
     * 없다. 조 표기에 제목을 넣지 않는 것은 모델이 그 긴 문자열을 옮겨 적다 틀리면 <b>맞는 인용이 버려지기</b> 때문이다.
     *
     * <p>페이지가 없는 형식(DOCX · #398)에서는 문서명이 표기다 — 「전부 1쪽」을 지어내지 않는다.
     */
    String marker() {
        if (citationType() == CitationType.ARTICLE) {
            String label = articleLabel();
            String article = label == null ? articleCitation() : label;
            return supplementary() ? "부칙 " + article : article;
        }
        Integer page = page();
        return page == null ? source.name() : "p." + page;
    }

    /** 인용의 `p.12`. <b>DOCX에는 없다</b> — 그때 인용은 문서명까지만 간다(#398) */
    Integer page() {
        return integer(RagChunkMetadata.PAGE);
    }

    private String string(String key) {
        Object value = chunk.getMetadata().get(key);
        return value == null ? null : String.valueOf(value);
    }

    /*
     * jsonb 왕복으로 수 타입이 바뀔 수 있어 `Number`로 받아 내린다. 문자열로 온 값도 읽는 것은
     * 한 번이라도 그렇게 저장된 청크가 있으면 그 문서의 인용이 통째로 사라지기 때문이다 —
     * 파싱에 실패하면 「그 key가 없다」와 같게 다룬다(대체값을 지어내지 않는다).
     */
    private Integer integer(String key) {
        Object value = chunk.getMetadata().get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** 청크가 어느 판본의 것인가 — 소유 관계의 열쇠 하나(`RagChunkStore.RAG_DOCUMENT_ID_KEY`) */
    static Long ragDocumentIdOf(Map<String, Object> metadata) {
        Object value = metadata.get(RagChunkStore.RAG_DOCUMENT_ID_KEY);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
