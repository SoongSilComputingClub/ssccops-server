package org.sscc.ssccopsserver.domain.assistant.dto;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.sscc.ssccopsserver.domain.assistant.code.RagApplyStatus;
import org.sscc.ssccopsserver.domain.assistant.code.RagDocumentType;
import org.sscc.ssccopsserver.domain.assistant.service.RagChunkMetadata;
import org.sscc.ssccopsserver.domain.assistant.service.RagChunkStore;

/*
 * 적재 단위 하나 — 고정 길이 600자 · overlap 100자 (#398 · 기획안 §5.4).
 *
 * `RegulationChunk`와 나란한 자리이고 `toDocument`의 계약도 같다: **판본의 값(`ragDocId` ·
 * `applyStatus`)은 청커가 알 수 없으므로** 판본을 아는 쪽(#400)이 굳히며, 그 한 메서드가
 * 메타데이터 key 이름을 아는 유일한 자리다.
 *
 * ── 왜 문서명이 임베딩 텍스트에 붙는가 ──────────────────────
 *
 * 조 단위 청크는 「제2장 회원 · 제7조」가 맥락을 말하지만 여기에는 그런 것이 없다. 600자를
 * 잘라 놓으면 그 조각이 학칙인지 지원금 지침인지가 본문 안에 없을 수 있고, 그러면 「지원금
 * 한도」 질의가 학칙 조각을 물어 온다. 표시명(`rag_doc.doc_nm`)은 운영진이 고칠 수 있는 값이라
 * **재색인 때 다시 찍힌다** — `applyStatus`가 전환마다 다시 찍히는 것과 같은 이유다.
 */
public record GenericChunk(int sequence, Integer page, String documentName, String body) {

    /**
     * 인용 표기 — `p.12`. <b>페이지가 없는 형식(DOCX)에서는 `null`이다.</b>
     *
     * <p>대체값을 만들지 않는다. 화면은 `citationType=PAGE`에 `page`가 비어 있으면 문서명만으로 인용을 그린다(§6.3).
     */
    public String citation() {
        return page == null ? null : "p." + page;
    }

    /** 임베딩 텍스트 맨 앞의 한 줄 — `2026 지원금 집행 지침 · p.12` */
    public String heading() {
        return page == null ? documentName : documentName + " · " + citation();
    }

    /** 저장되는 본문 — 헤더 한 줄 + 조각 */
    public String text() {
        return heading() + "\n" + body;
    }

    /** 판본을 아는 쪽이 부른다. 값이 `null`인 key는 넣지 않는다 — jsonb의 `null`은 필터에서 «있음»으로 세어진다 */
    public Document toDocument(long ragDocumentId, RagApplyStatus applyStatus) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(RagChunkStore.RAG_DOCUMENT_ID_KEY, ragDocumentId);
        metadata.put(RagChunkMetadata.DOC_TYPE, RagDocumentType.GENERIC.name());
        metadata.put(RagChunkMetadata.APPLY_STATUS, applyStatus.name());
        metadata.put(RagChunkMetadata.SEQUENCE, sequence);
        if (page != null) {
            metadata.put(RagChunkMetadata.PAGE, page);
        }
        return new Document(text(), metadata);
    }
}
