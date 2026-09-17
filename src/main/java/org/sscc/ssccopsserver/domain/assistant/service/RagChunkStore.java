package org.sscc.ssccopsserver.domain.assistant.service;

import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;

/*
 * 청크의 적재·삭제·검색 — 벡터 저장소 앞의 포트 (#396).
 *
 * ── 왜 포트인가 ───────────────────────────────────────────────
 *
 * **테스트가 실제 PostgreSQL 없이 돌기 때문이다.** `test` 프로필은 H2 + `ddl-auto: create`이고
 * pgvector가 없어 `VectorStore` 빈이 아예 서지 않는다(그래서 그 자동 구성을 제외한다 —
 * `GeminiWiringEnvironmentPostProcessor`). 그 상태에서 규정 도우미의 서비스를 검증하려면
 * 저장소 자리에 스텁이 들어가야 하는데, `@MockitoBean`으로 꽂으면 **그 테스트마다 스프링
 * 컨텍스트가 하나씩 갈린다** — #103이 58개를 25개로 줄여 놓은 이득을 되돌리는 방향이다.
 * 인터페이스로 두면 `@TestConfiguration` 한 벌을 여러 테스트가 함께 import 해 컨텍스트 하나를
 * 나눠 쓴다(ADR-0009의 `TestJwtDecoderConfig`와 같은 모양).
 *
 * ── 왜 Spring AI 타입을 그대로 쓰는가 ──────────────────────────
 *
 * 우리 타입으로 한 겹 감싸는 안은 택하지 않았다. 이 포트가 격리하려는 것은 «프레임워크»가 아니라
 * «실제 PostgreSQL 연결»이고, 청크를 만드는 쪽(#397·#398 파서·청커)과 검색하는 쪽(#403)이 이미
 * `Document`를 다룬다 — 경계에서만 변환하면 같은 값에 두 모양이 생기고 그 변환이 곧 다음 버그다.
 *
 * ── 구현 ─────────────────────────────────────────────────────
 *
 * 운영은 `PgVectorRagChunkStore`(`AssistantConfig`가 배선한다) 하나뿐이다. 벡터 저장소의 스키마는
 * Flyway가 만들고(`V10`) 스타터의 자동 생성은 꺼져 있다.
 */
public interface RagChunkStore {

    /**
     * 청크가 어느 판본의 것인지를 말하는 메타데이터 key.
     *
     * <p><b>이 값이 소유 관계의 전부다.</b> `vector_store`는 프레임워크의 테이블이라 컬럼을 더할 수 없어 FK가 없고, 재색인·삭제는 이 key로
     * 지운다. 청크를 만드는 쪽(#397·#398)이 반드시 찍어야 하며, 빠뜨린 청크는 <b>아무도 지울 수 없는 고아</b>가 된다.
     */
    String RAG_DOCUMENT_ID_KEY = "ragDocId";

    /** 청크를 넣는다. 임베딩은 저장소가 부른다(모델 호출이 여기서 일어난다는 뜻이다 — 배치 크기와 쿼터가 걸리는 자리) */
    void add(List<Document> chunks);

    /**
     * 그 판본의 청크를 전부 지운다.
     *
     * <p><b>재색인은 새 청크를 넣기 직전에 이것을 부른다</b>(#400) — 순서를 뒤집으면 중간에 실패했을 때 같은 조가 두 번 검색된다. 지울 것이 없어도 실패가
     * 아니다.
     */
    void deleteByRagDocumentId(long ragDocumentId);

    /** 유사도 검색. 판본 조건(`INDEXED AND EFFECTIVE`)은 조회 뒤 거르지 않고 요청의 필터에 넣는다 (#403) */
    List<Document> search(SearchRequest request);
}
