package org.sscc.ssccopsserver.domain.assistant.service;

/*
 * `vector_store.metadata`에 실리는 key 이름 (#397).
 *
 * **한 곳에 모은 것은 이 이름이 세 자리에서 쓰이기 때문이다** — 청크를 만드는 쪽(#397 구조화 ·
 * #398 일반), 색인 워커(#400), 검색 필터(#403). 문자열을 각자 적으면 «필터는 `applyStatus`를
 * 찾는데 적재는 `apply_status`로 넣었다»가 조용히 성립하고, 그 고장은 검색 결과가 0건인 것으로만
 * 드러난다.
 *
 * **판본 식별자(`ragDocId`)는 여기 없다** — 소유 관계의 열쇠라 포트가 갖는다
 * (`RagChunkStore.RAG_DOCUMENT_ID_KEY`). 같은 사실을 두 벌 적지 않는다.
 *
 * 값이 `null`인 key는 아예 넣지 않는다(가지번호 없는 조의 `articleBranchNumber` 등) —
 * `Document`가 null 값을 거부하고, jsonb에 `null`이 든 key는 필터에서 «있음»으로 세어진다.
 */
public final class RagChunkMetadata {

    /** `RagDocumentType` 이름. 인용이 조항이냐 페이지냐를 이 값이 가른다 (§6.3) */
    public static final String DOC_TYPE = "docType";

    /** `RagApplyStatus` 이름. 검색은 조회 뒤 거르지 않고 이 key로 필터한다 (#403) */
    public static final String APPLY_STATUS = "applyStatus";

    /** 판본 안에서의 청크 순번(0부터). 같은 조가 둘로 갈렸을 때 원문 순서를 되살리는 값이다 */
    public static final String SEQUENCE = "sequence";

    /*
     * 인용의 `p.12` — 그 청크가 **처음으로 담는 새 내용**의 페이지 (#398 · 기획안 §5.4).
     *
     * 청크가 페이지 경계를 넘으면 시작 페이지 하나만 적는다 — 둘을 다 적으면 인용이 길어지고
     * 운영진이 확인하러 여는 것은 시작 페이지다. **페이지가 없는 형식(DOCX)에는 key 자체가 없다.**
     */
    public static final String PAGE = "page";

    /** `제2장 회원` · `부칙` — 개정 마커를 뗀 장 제목 */
    public static final String CHAPTER = "chapter";

    /** 부칙 여부. 이것이 없으면 `제1조`가 두 곳을 가리킨다 (§5.3) */
    public static final String SUPPLEMENTARY = "supplementary";

    /** 조번호 */
    public static final String ARTICLE_NUMBER = "articleNumber";

    /** 가지번호 — `제27조의2`의 2. 없으면 key 자체가 없다 */
    public static final String ARTICLE_BRANCH_NUMBER = "articleBranchNumber";

    /** 원문 표기 그대로의 조 번호 — `제27조의2` */
    public static final String ARTICLE_LABEL = "articleLabel";

    /** 조 제목 — `회원의 구분`. 제목이 없는 조가 있으므로 key가 없을 수 있다 */
    public static final String ARTICLE_TITLE = "articleTitle";

    /** 인용 표기 — `제7조 (회원의 구분)` · `부칙 제3조 (의결의 순서)` */
    public static final String CITATION = "citation";

    /** 개정 마커 — `개정`·`신설`·`삭제`·`이동`·`전부개정`. 없으면 key가 없다 */
    public static final String REVISION_MARKER = "revisionMarker";

    private RagChunkMetadata() {}
}
