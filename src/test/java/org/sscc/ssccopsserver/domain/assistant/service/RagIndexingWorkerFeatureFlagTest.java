package org.sscc.ssccopsserver.domain.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.sscc.ssccopsserver.domain.assistant.repository.RagDocumentRepository;
import org.sscc.ssccopsserver.domain.file.service.FileDownloader;
import org.sscc.ssccopsserver.domain.file.service.FileReferenceService;

/*
 * 기능 플래그가 **워커도 함께 닫는가** (#400 · #396).
 *
 * **질의만 닫으면 워커가 계속 임베딩을 부르는데, 끄는 이유가 대개 쿼터다.** 그래서 꺼진 상태의
 * 워커는 대기열을 조회조차 하지 않아야 한다 — 조회만 하고 돌아오면 폴링 주기마다 DB를 때린다.
 *
 * 컨텍스트를 하나 더 띄우지 않는다(플래그 값이 다르면 컨텍스트가 갈린다) — 확인하려는 것이
 * 배선이 아니라 «꺼져 있으면 아무것도 하지 않는다» 한 줄이라 생성자로 충분하다
 * (`MemberDeletionServiceImplTest`가 꺼진 하드 삭제를 확인하는 방법과 같다).
 */
class RagIndexingWorkerFeatureFlagTest {

    private final RagDocumentRepository ragDocumentRepository = mock(RagDocumentRepository.class);
    private final FileReferenceService fileReferenceService = mock(FileReferenceService.class);
    private final FileDownloader fileDownloader = mock(FileDownloader.class);
    private final RagChunkStore ragChunkStore = mock(RagChunkStore.class);

    private final RagIndexingWorker worker =
            new RagIndexingWorker(
                    ragDocumentRepository,
                    fileReferenceService,
                    fileDownloader,
                    new RegulationParser(),
                    new GenericTextExtractor(),
                    new DocumentChunker(new RegulationChunker()),
                    new AssistantFeature(false),
                    emptyProvider(),
                    Clock.systemUTC(),
                    mock(PlatformTransactionManager.class),
                    // 기능이 꺼져 있을 때를 보는 테스트라 이 값은 쓰이지 않는다 (#556)
                    Duration.ofMinutes(10));

    @Test
    void doesNothingWhileTheAssistantIsDisabled() {
        assertThat(worker.drainQueue()).isZero();
        assertThat(worker.recoverStuckIndexing()).isZero();

        verifyNoInteractions(ragDocumentRepository, fileDownloader, ragChunkStore);
    }

    /** 저장소 빈이 있든 없든 답이 같아야 한다 — 꺼진 워커는 그것을 묻지도 않는다 */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<RagChunkStore> emptyProvider() {
        return mock(ObjectProvider.class);
    }
}
