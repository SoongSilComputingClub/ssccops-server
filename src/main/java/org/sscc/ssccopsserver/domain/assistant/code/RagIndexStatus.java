package org.sscc.ssccopsserver.domain.assistant.code;

/*
 * rag_doc.indx_stts_cd — 색인 진행 (#396 · ADR-0029).
 *
 * **적용 상태(`RagApplyStatus`)와 다른 축이다.** 한 컬럼에 겹치면 «색인은 끝났지만 아직 시행
 * 전인 개정안»을 표현할 수 없는데, 첫 업로드 대상이 바로 그것이다. 두 축이라서 «색인 중인
 * 문서를 질의가 보는가»에도 답할 수 있다 — 검색 조건이 `INDEXED AND EFFECTIVE` 둘이다.
 *
 * **사람이 정하는 값이 아니라 워커가 적는 값이다.** 그래서 이 컬럼만 고치는 API를 열지 않으며,
 * 화면의 «재색인»은 상태를 지정하는 것이 아니라 다시 줄을 세우는 조작이다(`PENDING`으로 돌린다).
 *
 * 전이표가 여기 있고 어기는 요청을 거절하는 것은 엔티티다(`RagDocumentEntity`) — 서비스에
 * 옮겨 적으면 색인 경로가 늘 때마다 규칙이 복제된다(`FormEntity.changeStatus`와 같은 자리).
 */
public enum RagIndexStatus {

    /** 행은 생겼고 워커가 아직 집지 않았다. 업로드 응답이 201 + 이 값이다 */
    PENDING,

    /** 워커가 임베딩 중. 동시 실행 1건이라 밀리면 PENDING으로 줄을 선다 */
    INDEXING,

    /** 청크가 벡터에 들어갔다. 검색 조건 둘 중 하나이며 이 값이라야 EFFECTIVE로 올릴 수 있다 */
    INDEXED,

    /** fail_rsn_cn에 사유. **자동 재시도를 하지 않는다** — 실패의 대부분이 쿼터·문서 자체이고 자동 재시도는 쿼터 소진을 가속한다 */
    FAILED;

    /**
     * 이 상태에서 {@code next}로 갈 수 있는가. 어기면 엔티티가 400으로 끊는다.
     *
     * <p>{@code INDEXING → PENDING}이 있는 것은 <b>부팅 복구</b> 때문이다(기획안 §12.4) — Supabase Free의 일시정지·배포
     * 재시작으로 색인 중이던 행이 그대로 남을 수 있고, 기동 시 그것을 되돌린다. 인스턴스가 하나라 성립하는 규칙이며 둘이 되면 남의 진행 중 작업을 되돌린다.
     *
     * <p>{@code INDEXED → PENDING}·{@code FAILED → PENDING}은 화면의 «재색인»이다 — 사람의 판단을 거친 재시도.
     */
    public boolean canTransitionTo(RagIndexStatus next) {
        return switch (this) {
            case PENDING -> next == INDEXING;
            case INDEXING -> next == INDEXED || next == FAILED || next == PENDING;
            case INDEXED, FAILED -> next == PENDING;
        };
    }
}
