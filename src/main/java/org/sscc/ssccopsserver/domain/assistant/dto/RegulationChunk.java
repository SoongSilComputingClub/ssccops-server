package org.sscc.ssccopsserver.domain.assistant.dto;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.sscc.ssccopsserver.domain.assistant.code.RagApplyStatus;
import org.sscc.ssccopsserver.domain.assistant.code.RagDocumentType;
import org.sscc.ssccopsserver.domain.assistant.service.RagChunkMetadata;
import org.sscc.ssccopsserver.domain.assistant.service.RagChunkStore;

/*
 * 적재 단위 하나 — 조 1개, 긴 조는 항 묶음 1개 (#397 · 기획안 §5.3).
 *
 * ── 왜 `Document`를 바로 담지 않는가 ──────────────────────────
 *
 * 메타의 절반(`ragDocId` · `applyStatus`)이 **판본의 값**이라 파서가 알 수 없다. 파서가 임의의
 * 값으로 채우면 그 자리에 «0번 판본»이 생기고, 빠뜨리면 지울 수 없는 고아 청크가 된다
 * (`RagChunkStore.RAG_DOCUMENT_ID_KEY`). 그래서 구조만 담고 판본을 아는 쪽(#400 색인 워커)이
 * {@link #toDocument}로 굳힌다 — 그 한 메서드가 key 이름을 아는 유일한 자리다.
 */
public record RegulationChunk(
        int sequence,
        String chapter,
        boolean supplementary,
        RegulationArticle article,
        String body) {

    /**
     * 인용 표기 — `제7조 (회원의 구분)` · `부칙 제3조 (의결의 순서)`.
     *
     * <p><b>부칙이면 «부칙»이 표기 안으로 들어온다.</b> 부칙에서 조번호가 1로 리셋되므로 `제1조`만으로는 본칙의 제1조(명칭)와 갈리지 않는다.
     */
    public String citation() {
        return supplementary ? "부칙 " + article.heading() : article.heading();
    }

    /**
     * 임베딩 텍스트 맨 앞의 한 줄 — `제2장 회원 · 제7조 (회원의 구분)`.
     *
     * <p><b>장을 붙이는 것은 조 혼자서는 맥락이 없기 때문이다.</b> «제28조 (설치)»가 무엇의 설치인지는 장이 말하고, 그 조는 개정안에서 제5장 재정 →
     * 제8장 동기회로 옮겨 갔다(`⟨이동⟩`) — 같은 조번호가 판본에 따라 다른 맥락을 갖는다.
     *
     * <p>부칙은 장 제목이 «부칙»이고 인용에 이미 들어 있어 앞에 한 번 더 적지 않는다.
     */
    public String heading() {
        return supplementary ? citation() : chapter + " · " + citation();
    }

    /** 저장되는 본문 — 헤더 한 줄 + 조문. <b>긴 조가 갈려도 헤더는 묶음마다 반복된다</b> */
    public String text() {
        return heading() + "\n" + body;
    }

    /** 판본을 아는 쪽이 부른다. `applyStatus`는 전환(#401) 때 바뀌므로 재색인이 그것을 다시 찍는다 */
    public Document toDocument(long ragDocumentId, RagApplyStatus applyStatus) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(RagChunkStore.RAG_DOCUMENT_ID_KEY, ragDocumentId);
        metadata.put(RagChunkMetadata.DOC_TYPE, RagDocumentType.STRUCTURED.name());
        metadata.put(RagChunkMetadata.APPLY_STATUS, applyStatus.name());
        metadata.put(RagChunkMetadata.SEQUENCE, sequence);
        metadata.put(RagChunkMetadata.CHAPTER, chapter);
        metadata.put(RagChunkMetadata.SUPPLEMENTARY, supplementary);
        metadata.put(RagChunkMetadata.ARTICLE_NUMBER, article.number());
        metadata.put(RagChunkMetadata.ARTICLE_LABEL, article.label());
        metadata.put(RagChunkMetadata.CITATION, citation());
        putIfPresent(metadata, RagChunkMetadata.ARTICLE_BRANCH_NUMBER, article.branchNumber());
        putIfPresent(metadata, RagChunkMetadata.ARTICLE_TITLE, article.title());
        putIfPresent(metadata, RagChunkMetadata.REVISION_MARKER, article.revisionMarker());
        return new Document(text(), metadata);
    }

    private static void putIfPresent(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }
}
