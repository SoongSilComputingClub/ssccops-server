package org.sscc.ssccopsserver.domain.assistant.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/*
 * 청크를 **언제** 지우는가 (#401).
 *
 * 통합 테스트로는 확인할 수 없는 자리다 — 삭제가 커밋 뒤에 일어나는데 통합 테스트는
 * `@Transactional`이라 그 시점이 오지 않는다(`FileReferenceUpsertEraseTest`와 같은 이유).
 * 잘못되면 되돌릴 수 없는 쪽으로 잘못된다: 트랜잭션 안에서 지우고 롤백되면 «행은 시행 중인데
 * 청크가 없는» 조합이 남고, 그것이 이 도메인이 가장 피하려는 고장이다.
 */
class RagChunkEraserTest {

    private static final long RAG_DOC_ID = 7L;

    @SuppressWarnings("unchecked")
    private final ObjectProvider<RagChunkStore> provider = mock(ObjectProvider.class);

    private final RagChunkStore chunkStore = mock(RagChunkStore.class);
    private final RagChunkEraser eraser = new RagChunkEraser(provider);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** 트랜잭션이 없으면 되돌아갈 것이 없으므로 즉시 지운다 */
    @Test
    void erasesImmediatelyWithoutTransaction() {
        when(provider.getIfAvailable()).thenReturn(chunkStore);

        eraser.eraseAfterCommit(RAG_DOC_ID);

        verify(chunkStore).deleteByRagDocumentId(RAG_DOC_ID);
    }

    /*
     * **커밋 전까지 지우지 않는다.** 롤백된 전환·삭제 뒤에 청크만 사라지면 그 판본은 «시행 중인데
     * 검색되지 않는 문서»가 된다 — 화면에는 아무 일도 없었던 것으로 보이는데 답변만 달라진다.
     */
    @Test
    void doesNotEraseBeforeCommit() {
        when(provider.getIfAvailable()).thenReturn(chunkStore);
        TransactionSynchronizationManager.initSynchronization();

        eraser.eraseAfterCommit(RAG_DOC_ID);

        verify(chunkStore, never()).deleteByRagDocumentId(RAG_DOC_ID);
    }

    /** 커밋되면 그때 지운다 */
    @Test
    void erasesAfterCommit() {
        when(provider.getIfAvailable()).thenReturn(chunkStore);
        TransactionSynchronizationManager.initSynchronization();
        eraser.eraseAfterCommit(RAG_DOC_ID);

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(synchronization -> synchronization.afterCommit());

        verify(chunkStore).deleteByRagDocumentId(RAG_DOC_ID);
    }

    /*
     * **삭제 실패가 밖으로 나가지 않는다.** 남은 청크는 검색되지 않으므로(판본 행이 없거나
     * `SUPERSEDED`라 필터에 걸리지 않는다) 비용이지 잘못된 답이 아니고, 커밋 뒤에 던지면 그
     * 예외가 갈 곳도 없다 — `FileEraser`와 같은 태도다.
     */
    @Test
    void swallowsFailure() {
        when(provider.getIfAvailable()).thenReturn(chunkStore);
        doThrow(new IllegalStateException("벡터 저장소 연결 실패"))
                .when(chunkStore)
                .deleteByRagDocumentId(RAG_DOC_ID);

        assertThatCode(() -> eraser.eraseAfterCommit(RAG_DOC_ID)).doesNotThrowAnyException();
    }

    /*
     * **저장소 빈이 없을 수 있다** — Gemini 키가 없으면 `RagChunkStore`가 서지 않는다
     * (`AssistantConfig`). 그 서버에는 청크가 들어간 적이 없으므로 지울 것도 없고, 그 사실이
     * 삭제·전환을 막지도 않는다.
     */
    @Test
    void doesNothingWhenChunkStoreIsNotWired() {
        when(provider.getIfAvailable()).thenReturn(null);

        assertThatCode(() -> eraser.eraseAfterCommit(RAG_DOC_ID)).doesNotThrowAnyException();
    }
}
