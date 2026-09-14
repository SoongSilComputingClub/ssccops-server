package org.sscc.ssccopsserver.domain.assistant.code;

/*
 * rag_doc.aplcn_stts_cd — 적용 여부 (#396 · ADR-0029).
 *
 * **색인 진행(`RagIndexStatus`)과 다른 축이다** — 근거는 그쪽 주석에 있다.
 *
 * **올린 것이 곧바로 답변의 근거가 되지 않게 하는 층이다.** 레포 커밋 코퍼스를 버리면서(ADR-0029)
 * «코드 리뷰를 거친 문서만 적재된다»가 사라졌고, 그 자리를 «업로드는 언제나 DRAFT로 들어오고
 * EFFECTIVE로 올리는 것이 별도 조작이다»가 대신한다. 화면에 미리보기 단계가 없는 대신 이것이
 * 그 역할을 맡는다.
 *
 * **DRAFT·SUPERSEDED는 검색에 잡히지 않는다.** 조회한 뒤 거르는 것이 아니라 검색 필터에 넣는다.
 */
public enum RagApplyStatus {

    /** 개정안 — 의결 전·검토용. 업로드는 언제나 이 값이다 */
    DRAFT,

    /** 시행 중 — 지금 유효한 판본. **doc_cd당 최대 한 벌**이고 그 규칙은 부분 유니크 인덱스 + 애플리케이션 판정 두 겹이다 */
    EFFECTIVE,

    /** 옛 판본 — 새 판본으로 대체됨. 삭제가 하드인 근거가 이 값이다: 남길 값이 있는 옛 판본은 여기가 맡는다 */
    SUPERSEDED;

    /**
     * 이 상태에서 {@code next}로 갈 수 있는가. 어기면 엔티티가 400으로 끊는다.
     *
     * <p><b>{@code SUPERSEDED}는 종착점이다.</b> 옛 판본을 되살리는 길을 열면 «지금 유효한 규정»이 무엇인지에 답이 둘이 된다 — 되돌리려면 그
     * 파일을 새 판본으로 다시 올린다(원본이 R2에 있다).
     *
     * <p>{@code EFFECTIVE}로 가려면 색인이 끝나 있어야 한다. 그 판정은 다른 축의 값을 보는 것이라 이 표가 아니라 엔티티가 한다 — 어기면 «시행 중인데
     * 검색되지 않는 문서»가 되어 도우미가 근거 없이 침묵한다.
     */
    public boolean canTransitionTo(RagApplyStatus next) {
        return switch (this) {
            case DRAFT -> next == EFFECTIVE;
            case EFFECTIVE -> next == SUPERSEDED;
            case SUPERSEDED -> false;
        };
    }
}
