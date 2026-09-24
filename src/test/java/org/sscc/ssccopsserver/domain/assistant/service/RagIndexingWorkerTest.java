package org.sscc.ssccopsserver.domain.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.sscc.ssccopsserver.domain.assistant.code.RagApplyStatus;
import org.sscc.ssccopsserver.domain.assistant.code.RagDocumentType;
import org.sscc.ssccopsserver.domain.assistant.code.RagIndexStatus;
import org.sscc.ssccopsserver.domain.assistant.entity.RagDocumentEntity;
import org.sscc.ssccopsserver.domain.assistant.repository.RagDocumentRepository;
import org.sscc.ssccopsserver.domain.file.code.FileTargetType;
import org.sscc.ssccopsserver.domain.file.repository.FileReferenceRepository;
import org.sscc.ssccopsserver.domain.file.service.FileReferenceService;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberGradeRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.member.repository.MemberStatusRepository;
import org.sscc.ssccopsserver.support.AssistantStubConfig;
import org.sscc.ssccopsserver.support.InMemoryRagChunkStore;
import org.sscc.ssccopsserver.support.MemberFixture;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/*
 * 색인 워커 (#400 · 상위 ssccops#326).
 *
 * 확인의 중심은 **상태 전이와 그 전이가 남기는 것**이다 — `PENDING`을 집어 `INDEXED`로 올리고
 * 청크 수·시각을 적는가, 실패가 `FAILED` + `fail_rsn_cn`으로 남는가(워커에는 돌려줄 응답이
 * 없다), 재색인이 옛 청크를 남기지 않는가, 기동 복구가 멈춘 행을 되돌리는가.
 *
 * **자동 실행은 꺼져 있다**(`application-test.yaml`의 `ssccops.assistant.indexing.auto=false`) —
 * 여기서는 워커 메서드를 직접 부른다. 켜 두면 컨텍스트가 뜨는 순간 폴링 스레드가 같은 행을
 * 집어 이 테스트들과 경합한다.
 *
 * **`@Transactional`을 걸지 않는다.** 워커는 «집기 → 임베딩 → 적기»를 트랜잭션 셋으로 나누고
 * 잠금이 그 사이를 지킨다 — 테스트 트랜잭션 하나에 묶으면 그 구조가 통째로 사라진다. 그래서
 * 전용 H2 DB에서 실제로 커밋한다(`AuditPointsTest`와 같은 이유).
 *
 * **S3Client를 목으로 갈아 끼운다** — 진짜 빈은 R2 자격을 요구하고, 여기서 확인하려는 것은
 * 내려받기가 아니라 «내려받은 바이트로 무엇을 하는가»다.
 */
@SpringBootTest(
        properties = {
            "ssccops.assistant.enabled=true",
            "spring.datasource.url="
                    + "jdbc:h2:mem:rag-indexing;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
        })
@ActiveProfiles("test")
@Import(AssistantStubConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RagIndexingWorkerTest {

    /** 계약을 지키는 최소 회칙 — 장 하나·조 하나 */
    private static final String VALID_MARKDOWN =
            """
            # SSCC 동아리 회칙

            ## 제1장 총칙

            ### 제1조 (명칭)

            본 회의 명칭은 숭실 컴퓨팅 클럽이라 한다.

            ### 제2조 (목적)

            본 회는 컴퓨팅 분야의 학술 활동을 목적으로 한다.
            """;

    private static final String OBJECT_KEY = "rag-documents/1/original.md";

    @Autowired private RagIndexingWorker worker;
    @Autowired private RagDocumentRepository ragDocumentRepository;
    @Autowired private FileReferenceRepository fileReferenceRepository;
    @Autowired private FileReferenceService fileReferenceService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberGradeRepository memberGradeRepository;
    @Autowired private MemberStatusRepository memberStatusRepository;
    @Autowired private InMemoryRagChunkStore chunkStore;

    @MockitoBean private S3Client r2Client;

    private MemberEntity registrant;

    /* 트랜잭션이 없어 픽스처가 DB에 남는다 — 클래스당 한 번만 세운다 */
    @BeforeAll
    void fixtures() {
        registrant =
                MemberFixture.save(
                        memberRepository,
                        memberGradeRepository,
                        memberStatusRepository,
                        UUID.randomUUID(),
                        "20260401",
                        "규정관리자",
                        "20260401@sscc.org");
    }

    @BeforeEach
    void reset() {
        fileReferenceRepository.deleteAll();
        ragDocumentRepository.deleteAll();
        chunkStore.clear();
    }

    // ------------------------------------------------------------------ 통과

    /*
     * `PENDING` 한 건이 **`INDEXED` + 청크 수 + 시작·종료 시각**이 된다.
     *
     * 화면이 목록에서 읽는 값이 그것이고(§13.2), 시각 둘은 **실측 없이 배치 크기를 조정할 수
     * 없기** 때문에 적는다(§12.4).
     */
    @Test
    void indexesPendingDocumentAndRecordsChunkCountAndTimestamps() {
        RagDocumentEntity document = pendingMarkdown("REGULATION", VALID_MARKDOWN);

        assertThat(worker.drainQueue()).isEqualTo(1);

        RagDocumentEntity indexed = reload(document);
        assertThat(indexed.getIndexStatus()).isEqualTo(RagIndexStatus.INDEXED);
        assertThat(indexed.getChunkCount()).isEqualTo(chunkStore.chunks().size()).isPositive();
        assertThat(indexed.getIndexStartedAt()).isNotNull();
        assertThat(indexed.getIndexEndedAt()).isNotNull();
        assertThat(indexed.getFailureReason()).isNull();
        assertThat(indexed.getApplyStatus())
                .as("색인은 적용 상태를 건드리지 않는다 — 두 축이다")
                .isEqualTo(RagApplyStatus.DRAFT);
    }

    /*
     * 청크에 **판본의 값이 찍힌다** — `ragDocId`가 없으면 아무도 지울 수 없는 고아가 되고,
     * `applyStatus`가 없으면 검색이 시행본만 고를 수 없다(#397 · #403).
     */
    @Test
    void stampsVersionMetadataOnEveryChunk() {
        RagDocumentEntity document = pendingMarkdown("REGULATION", VALID_MARKDOWN);

        worker.drainQueue();

        List<Document> chunks = chunkStore.chunks();
        assertThat(chunks).isNotEmpty();
        assertThat(chunks)
                .allSatisfy(
                        chunk -> {
                            assertThat(chunk.getMetadata().get(RagChunkStore.RAG_DOCUMENT_ID_KEY))
                                    .isEqualTo(document.getId());
                            assertThat(chunk.getMetadata().get(RagChunkMetadata.APPLY_STATUS))
                                    .isEqualTo(RagApplyStatus.DRAFT.name());
                            assertThat(chunk.getMetadata().get(RagChunkMetadata.DOC_TYPE))
                                    .isEqualTo(RagDocumentType.STRUCTURED.name());
                        });
    }

    /*
     * `GENERIC` 갈래도 같은 워커가 집는다 — **청킹 규칙이 둘이라는 사실을 워커가 몰라도 된다**는
     * 것이 `DocumentChunker`를 한 자리로 둔 이유다(#398). 골든셋 PDF의 청크 11개가 그 증인이며,
     * 쪽 메타가 실려 인용이 `p.N`이 된다.
     */
    @Test
    void indexesGenericPdfWithPageMetadata() throws Exception {
        byte[] pdf = resource("rag/regulation-current.pdf");
        RagDocumentEntity document = pending("2026 동아리 회칙", "regulation-current.pdf", pdf);

        assertThat(worker.drainQueue()).isEqualTo(1);

        assertThat(reload(document).getChunkCount())
                .as("6,093자를 600자·overlap 100자로 자른 결과 (GenericGoldenSetTest와 같은 값)")
                .isEqualTo(11);
        assertThat(chunkStore.chunks())
                .first()
                .satisfies(
                        chunk ->
                                assertThat(chunk.getMetadata().get(RagChunkMetadata.PAGE))
                                        .isEqualTo(1));
    }

    /*
     * **재색인은 새 청크를 넣기 직전에 옛 청크를 지운다.** 순서를 뒤집으면 중간에 실패했을 때
     * 같은 조가 두 번 검색된다(§12.4).
     *
     * 재색인이 «`PENDING`으로 다시 줄을 세우는 것»뿐이라는 사실도 여기 함께 걸린다 — 워커가
     * 그 상태만 집으므로 전용 경로가 없다.
     */
    @Test
    void reindexingReplacesOldChunksInsteadOfDuplicatingThem() {
        RagDocumentEntity document = pendingMarkdown("REGULATION", VALID_MARKDOWN);
        worker.drainQueue();
        int first = chunkStore.chunks().size();

        requeue(document);
        assertThat(worker.drainQueue()).isEqualTo(1);

        assertThat(chunkStore.chunks()).as("옛 청크가 남았다면 두 배가 된다").hasSize(first);
        assertThat(reload(document).getChunkCount()).isEqualTo(first);
        assertThat(chunkStore.operations())
                .as("두 바퀴의 순서 — 지우는 것이 언제나 넣기 **앞**이다 (#405 · 기획안 §14.3)")
                .containsExactly(
                        "delete:" + document.getId(),
                        "add:" + first,
                        "delete:" + document.getId(),
                        "add:" + first);
    }

    /*
     * **`PENDING → INDEXING → INDEXED`의 가운데가 실제로 존재한다** (#405 · 기획안 §14.3).
     *
     * 끝난 뒤의 상태만 보면 워커가 트랜잭션 셋으로 나뉜 이유가 검증되지 않는다 — 임베딩이 도는
     * 동안 행이 `INDEXING`으로 **남의 눈에 보여야** 다른 폴링이 그 행을 다시 집지 않고, 기동
     * 복구(§12.4)가 되돌릴 대상도 그 상태다. 적재 도중에 끼어들어 그때의 행을 읽는 것이
     * 잠금 밖에서 그 사실을 볼 수 있는 유일한 자리다.
     */
    @Test
    void staysInIndexingWhileTheEmbeddingRuns() {
        RagDocumentEntity document = pendingMarkdown("REGULATION", VALID_MARKDOWN);
        List<RagIndexStatus> seen = new ArrayList<>();
        chunkStore.observeAdds(() -> seen.add(reload(document).getIndexStatus()));

        assertThat(worker.drainQueue()).isEqualTo(1);

        assertThat(seen).as("적재가 도는 동안의 상태").containsExactly(RagIndexStatus.INDEXING);
        assertThat(reload(document).getIndexStatus()).isEqualTo(RagIndexStatus.INDEXED);
    }

    /*
     * 기동 복구 — `INDEXING`에 멈춘 행을 `PENDING`으로 되돌린다(§12.4). **Supabase Free의
     * 일시정지와 배포 재시작이 실제 원인이고**, 되돌리지 않으면 그 행을 집을 사람이 없다.
     *
     * **한 시간 전에 집힌 것으로 만든다** (#556). 복구가 나이를 보게 되어(기본 10분) 방금 집힌
     * 행은 되돌리지 않는다 — 이 테스트는 «진짜로 죽은 작업»을 말하므로 그만큼 오래된 값을 준다.
     */
    @Test
    void bootRecoveryRequeuesDocumentsStuckInIndexing() {
        RagDocumentEntity document = pendingMarkdown("REGULATION", VALID_MARKDOWN);
        startIndexingAt(document, Instant.now().minusSeconds(3600));

        assertThat(worker.recoverStuckIndexing()).isEqualTo(1);

        RagDocumentEntity recovered = reload(document);
        assertThat(recovered.getIndexStatus()).isEqualTo(RagIndexStatus.PENDING);
        assertThat(recovered.getIndexStartedAt()).as("되돌렸으므로 «언제 집었나»도 지워진다").isNull();

        // 되돌린 행을 그다음 바퀴가 집는다 — 복구와 재색인이 같은 상태로 수렴하는 것이 요점이다
        assertThat(worker.drainQueue()).isEqualTo(1);
        assertThat(reload(document).getIndexStatus()).isEqualTo(RagIndexStatus.INDEXED);
    }

    /*
     * ⚠️ **방금 집힌 행은 되돌리지 않는다** (#556 · ssccops#501).
     *
     * 배포 중에는 컨테이너가 둘이다 — Coolify 가 새 것을 healthy 로 만든 뒤 옛 것을 내리므로
     * 겹침은 사고가 아니라 배포 절차 그 자체이고, 배포는 develop 푸시마다 일어난다. 조건 없는
     * 옛 복구는 **새로 뜬 쪽이 옛 쪽의 진행 중 색인을 되돌렸고**, 그 결과가 고아 청크와 같은
     * 문서의 재임베딩이었다(무료 티어 하루치가 두 번 나간다).
     */
    @Test
    void bootRecoveryLeavesDocumentsPickedUpJustNow() {
        RagDocumentEntity document = pendingMarkdown("REGULATION", VALID_MARKDOWN);
        startIndexingAt(document, Instant.now().minusSeconds(30));

        assertThat(worker.recoverStuckIndexing()).isZero();
        assertThat(reload(document).getIndexStatus()).isEqualTo(RagIndexStatus.INDEXING);
    }

    /*
     * **옛 판본은 상한에 세지 않는다**(§8.2) — 판본 교체 때 청크가 사라지므로, 세면 «지운 문서
     * 때문에 새 문서를 올리지 못하는» 상태가 된다.
     */
    @Test
    void supersededVersionsDoNotCountTowardTheChunkBudget() {
        supersededWithChunks("REGULATION", RagIndexingWorker.MAX_ACTIVE_CHUNKS);

        RagDocumentEntity document = pendingMarkdown("GUIDELINE", VALID_MARKDOWN);

        assertThat(worker.drainQueue()).isEqualTo(1);
        assertThat(reload(document).getIndexStatus()).isEqualTo(RagIndexStatus.INDEXED);
    }

    // ------------------------------------------------------------------ 실패

    /*
     * **실패는 응답이 아니라 상태다** — 워커는 요청 밖에서 돌아 돌려줄 응답이 없고
     * `fail_rsn_cn`이 오류 코드의 자리를 대신한다(§10). 원본이 사라진 경우가 그 대표다.
     */
    @Test
    void marksFailedWithReasonWhenTheOriginalIsGone() {
        RagDocumentEntity document = pendingMarkdown("REGULATION", VALID_MARKDOWN);
        when(r2Client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("no such key").build());

        assertThat(worker.drainQueue()).isZero();

        RagDocumentEntity failed = reload(document);
        assertThat(failed.getIndexStatus()).isEqualTo(RagIndexStatus.FAILED);
        assertThat(failed.getFailureReason()).contains("NoSuchKeyException");
        assertThat(failed.getIndexEndedAt()).isNotNull();
        assertThat(failed.getChunkCount()).as("색인되지 않았으므로 청크 수는 비어 있다").isNull();
        assertThat(chunkStore.chunks()).isEmpty();
    }

    /*
     * 파싱 실패도 같은 자리에 남고, **사유에 «몇째 줄이 왜»가 그대로 실린다**(#397 · #150) —
     * 운영진이 목록에서 읽고 파일을 고칠 수 있어야 하기 때문이다.
     */
    @Test
    void marksFailedWhenTheOriginalNoLongerParses() {
        RagDocumentEntity document = pendingMarkdown("REGULATION", "# 제목만 있고 조가 없다\n");

        assertThat(worker.drainQueue()).isZero();

        RagDocumentEntity failed = reload(document);
        assertThat(failed.getIndexStatus()).isEqualTo(RagIndexStatus.FAILED);
        assertThat(failed.getFailureReason()).contains("RAG_DOCUMENT_PARSE_FAILED").contains("조");
        assertThat(chunkStore.chunks()).isEmpty();
    }

    /*
     * **활성 청크 총량 상한**(3,000 · §8.2). 넘으면 적재하지 않고 `FAILED`다 — 임베딩을 수백 번
     * 치른 뒤에 끊지 않고 **넣기 직전에** 본다.
     */
    @Test
    void refusesToIndexBeyondTheActiveChunkBudget() {
        indexedWithChunks("SCHOOL_RULE", RagIndexingWorker.MAX_ACTIVE_CHUNKS);

        RagDocumentEntity document = pendingMarkdown("REGULATION", VALID_MARKDOWN);

        assertThat(worker.drainQueue()).isZero();

        RagDocumentEntity failed = reload(document);
        assertThat(failed.getIndexStatus()).isEqualTo(RagIndexStatus.FAILED);
        assertThat(failed.getFailureReason()).contains("RAG_DOCUMENT_LIMIT_EXCEEDED");
        assertThat(chunkStore.chunks()).as("상한을 넘겼으므로 적재 자체가 없다").isEmpty();
    }

    // ------------------------------------------------------------------ 도우미

    private RagDocumentEntity pendingMarkdown(String name, String markdown) {
        return pending(name, "회칙.md", markdown.getBytes(StandardCharsets.UTF_8));
    }

    /** 업로드가 남기는 것과 같은 상태를 만든다 — 행(`PENDING`·`DRAFT`) + `file_rfrnc` + R2의 바이트 */
    private RagDocumentEntity pending(String name, String fileName, byte[] content) {

        RagDocumentEntity document =
                ragDocumentRepository.save(
                        RagDocumentEntity.register(
                                name,
                                fileName.endsWith(".md")
                                        ? RagDocumentType.STRUCTURED
                                        : RagDocumentType.GENERIC,
                                fileName,
                                content.length,
                                registrant));

        fileReferenceService.upsert(FileTargetType.RAG_DOCUMENT, document.getId(), OBJECT_KEY);
        when(r2Client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(
                        ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), content));
        return document;
    }

    /** 상한 판정의 재료 — 이미 색인이 끝난 문서 하나가 청크 N개를 들고 있는 상태 */
    private void indexedWithChunks(String name, int chunkCount) {
        RagDocumentEntity document = pendingMarkdown(name, VALID_MARKDOWN);
        document.startIndexing(Instant.now());
        document.completeIndexing(chunkCount, Instant.now());
        ragDocumentRepository.save(document);
    }

    private void supersededWithChunks(String name, int chunkCount) {
        indexedWithChunks(name, chunkCount);
        RagDocumentEntity document =
                ragDocumentRepository.findAll().stream()
                        .filter(candidate -> candidate.getName().equals(name))
                        .findFirst()
                        .orElseThrow();
        document.makeEffective(LocalDate.now());
        document.supersede();
        ragDocumentRepository.save(document);
    }

    private void startIndexing(RagDocumentEntity document) {
        startIndexingAt(document, Instant.now());
    }

    /**
     * 집힌 시각을 지정해 색인 중으로 만든다 (#556).
     *
     * <p>기동 복구가 나이를 보게 되면서 «언제 집혔나»가 판정의 일부가 됐다 — 방금 집힌 행은 다른 인스턴스가 지금 돌리는 중이라는 뜻이라 되돌리지 않는다.
     */
    private void startIndexingAt(RagDocumentEntity document, Instant startedAt) {
        RagDocumentEntity loaded = reload(document);
        loaded.startIndexing(startedAt);
        ragDocumentRepository.save(loaded);
    }

    private void requeue(RagDocumentEntity document) {
        RagDocumentEntity loaded = reload(document);
        loaded.requeueIndexing();
        ragDocumentRepository.save(loaded);
    }

    private RagDocumentEntity reload(RagDocumentEntity document) {
        return ragDocumentRepository.findById(document.getId()).orElseThrow();
    }

    private static byte[] resource(String path) throws Exception {
        try (InputStream stream = new ClassPathResource(path).getInputStream()) {
            return stream.readAllBytes();
        }
    }
}
