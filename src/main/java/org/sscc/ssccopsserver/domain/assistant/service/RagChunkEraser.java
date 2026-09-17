package org.sscc.ssccopsserver.domain.assistant.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/*
 * 한 판본의 청크를 **커밋 뒤에** 지운다 (#401).
 *
 * ══ 왜 커밋 뒤인가 — `FileEraser`(#234)와 같은 규칙 ══════════════
 *
 * 벡터 저장소의 삭제에는 롤백이 없다. 트랜잭션 안에서 지우면 그 뒤 롤백된 변경 때문에 **«행은
 * 시행 중인데 청크가 없는» 조합**이 남는데, 그것이 이 도메인이 가장 피하려는 고장이다 — 화면에는
 * 반영됐다고 뜨는데 도우미만 근거 없이 침묵한다(`RAG_DOCUMENT_NOT_INDEXED`가 막으려는 것과 같은
 * 상태다). 되돌릴 수 없는 쪽을 뒤로 미루는 것이 업로드의 «파싱 → 행 → R2 PUT»과 같은 순서다.
 *
 * ══ 무엇이 부르는가 ════════════════════════════════════════════
 *
 * 둘이다. **하드 삭제**(행·청크·R2 오브젝트를 함께 지운다 · ADR-0029)와 **적용 전환에서 내려간
 * 판본**이다. 뒤의 것이 지우는 쪽인 이유는 `SUPERSEDED`가 종착점이고 검색 조건이
 * `INDEXED && EFFECTIVE`라 그 청크를 다시 볼 경로가 없기 때문이며, 그래서 활성 청크 합계
 * (`sumActiveChunkCount`)가 그 둘을 빼고 세는 것이 **실제와 맞는다** — 빼고 세면서 지우지 않으면
 * 상한 3,000이 실제 저장량보다 낮은 수를 보고 판정한다(#400 · §8.2).
 *
 * ══ 실패해도 본래 작업을 되돌리지 않는다 ═══════════════════════
 *
 * `FileEraser`와 같다. 남은 청크는 **검색되지 않는다**(판본 행이 없거나 `SUPERSEDED`라 필터에
 * 걸리지 않는다) — 비용이지 잘못된 답이 아니다. 커밋 뒤에 던져 봐야 그 예외가 갈 곳도 없다.
 *
 * ⚠️ **저장소 빈이 없을 수 있다.** Gemini 키가 없으면 `RagChunkStore`가 서지 않는다
 * (`AssistantConfig`) — 그 서버에는 애초에 청크가 들어간 적이 없으므로 지울 것도 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagChunkEraser {

    private final ObjectProvider<RagChunkStore> ragChunkStore;

    /** 커밋된 뒤에 지운다. 트랜잭션 밖에서 부르면 즉시 지운다 */
    public void eraseAfterCommit(long ragDocumentId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            erase(ragDocumentId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        erase(ragDocumentId);
                    }
                });
    }

    private void erase(long ragDocumentId) {
        RagChunkStore chunkStore = ragChunkStore.getIfAvailable();
        if (chunkStore == null) {
            return;
        }
        try {
            chunkStore.deleteByRagDocumentId(ragDocumentId);
            log.info("규정 문서의 청크를 지웠다 — ragDocId={}", ragDocumentId);
        } catch (RuntimeException exception) {
            // 삼키는 것이 의도다(클래스 주석). 남은 청크는 검색되지 않으므로 비용이지 결함이 아니다
            log.error("규정 문서의 청크 삭제 실패 — 고아로 남는다: ragDocId={}", ragDocumentId, exception);
        }
    }
}
