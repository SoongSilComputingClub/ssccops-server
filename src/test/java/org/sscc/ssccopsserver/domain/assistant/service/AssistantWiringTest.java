package org.sscc.ssccopsserver.domain.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.sscc.ssccopsserver.support.AssistantStubConfig;
import org.sscc.ssccopsserver.support.InMemoryRagChunkStore;

/*
 * 키가 없는 환경에서 컨텍스트가 뜨는지, 그리고 스텁이 포트 자리에 들어가는지 (#396 · #406).
 *
 * **이 테스트가 지키는 것은 «규정 도우미가 없는 서버도 정상»이다.** pgvector 자동 구성은
 * `EmbeddingModel` 빈을 생성자로 요구하는데 그 빈은 Gemini 키가 있을 때만 생기므로, 제외하지
 * 않으면 키를 넣지 않은 환경 — 테스트 · 기여자 로컬 · 아직 키를 넣지 않은 dev 배포 — 이
 * **규정 도우미와 아무 상관 없는 이유로 통째로 못 뜬다.** 그 실패는 여기서 «컨텍스트 로딩 실패»로
 * 드러난다.
 *
 * 컨텍스트가 하나 더 생기는 대가를 치르는 것은 스텁 배선이 실제로 서는지를 지금 확인하기
 * 위해서다 — #400·#403이 같은 `AssistantStubConfig`를 import 하면 그 컨텍스트를 함께 쓴다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(AssistantStubConfig.class)
class AssistantWiringTest {

    @Autowired private ApplicationContext context;

    @Autowired private RagChunkStore ragChunkStore;

    @Autowired private AssistantFeature assistantFeature;

    /* 키가 없으므로 모델·저장소 빈이 아예 없다. 있으면 스타터가 부팅 중에 Gemini를 부른다 */
    @Test
    void withoutAnApiKeyNeitherTheModelNorTheVectorStoreIsWired() {
        assertThat(context.getBeanNamesForType(VectorStore.class))
                .as("pgvector 자동 구성이 제외돼 있어야 한다 — 아니면 H2에서 컨텍스트가 아예 뜨지 못한다")
                .isEmpty();
        assertThat(context.getBeanNamesForType(ChatClient.class)).isEmpty();
        assertThat(context.getBeanNamesForType(EmbeddingModel.class))
                .as("감쌀 모델이 없으면 질의 임베딩 캐시도 서지 않는다 (#406)")
                .isEmpty();
    }

    /*
     * ⚠️ **상한 없는 기본 저장소가 물러나 있다** (#406 · 기획안 §7.3).
     *
     * Spring AI의 `ChatMemoryAutoConfiguration`은 `@ConditionalOnMissingBean`으로
     * `InMemoryChatMemoryRepository`를 세운다 — `conversationId`마다 항목을 쌓고 아무것도 꺼내지
     * 않으므로 **천장이 없다**(`MessageWindowChatMemory`가 자르는 것은 대화 하나의 턴 수다).
     * 우리 빈이 그 자리에 들어가 있다는 것이 §8.1의 12MB가 성립하는 전제이고, 이 테스트가 없으면
     * 그 자리를 잃어도 «대화가 되긴 하니까» 아무 데서도 드러나지 않는다.
     */
    @Test
    void ourBoundedStoreTakesTheChatMemoryRepositorySlot() {
        assertThat(context.getBean(ChatMemoryRepository.class))
                .isInstanceOf(AssistantMemoryStore.class);
    }

    /* 플래그는 기본이 꺼짐이고 test 프로필도 켜지 않는다 — 켜는 곳은 배포 환경변수 하나다 */
    @Test
    void featureFlagIsOff() {
        assertThat(assistantFeature.isEnabled()).isFalse();
    }

    /*
     * **색인 워커의 자동 실행이 test 프로필에서 꺼져 있다** (#400 · `application-test.yaml`).
     *
     * 켜져 있으면 이 컨텍스트가 뜨는 순간 폴링 스레드가 공용 `testdb`의 `PENDING` 행을 집고
     * 스텁 저장소에 청크를 넣는다 — 상태가 테스트 사이로 새어 나가는 자리이며, 상태 전이는
     * `RagIndexingWorkerTest`가 워커 메서드를 직접 불러 검증하므로 잃는 것이 없다.
     */
    @Test
    void automaticIndexingIsOffInTests() {
        assertThat(context.getBeanNamesForType(RagIndexingScheduler.class))
                .as("ssccops.assistant.indexing.auto=false면 스케줄러 빈 자체가 서지 않는다")
                .isEmpty();
    }

    /* 포트 자리에 스텁이 들어간다 — 이것이 실제 PostgreSQL 없이 도우미를 검증하는 방법이다 */
    @Test
    void theStubFillsThePort() {
        assertThat(ragChunkStore).isInstanceOf(InMemoryRagChunkStore.class);

        InMemoryRagChunkStore stub = (InMemoryRagChunkStore) ragChunkStore;
        stub.clear();
        stub.add(
                List.of(
                        new Document(
                                "제7조 (회원의 구분)", Map.of(RagChunkStore.RAG_DOCUMENT_ID_KEY, 1L))));

        assertThat(stub.search(SearchRequest.builder().query("회원").topK(5).build())).hasSize(1);

        stub.deleteByRagDocumentId(1L);
        assertThat(stub.chunks()).as("재색인은 새 청크를 넣기 직전에 옛 청크를 지운다").isEmpty();
    }
}
