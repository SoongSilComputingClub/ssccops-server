package org.sscc.ssccopsserver.domain.assistant.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.sscc.ssccopsserver.domain.assistant.code.RagApplyStatus;
import org.sscc.ssccopsserver.domain.assistant.code.RagDocumentType;
import org.sscc.ssccopsserver.domain.assistant.code.RagIndexStatus;
import org.sscc.ssccopsserver.domain.assistant.code.error.AssistantErrorCode;
import org.sscc.ssccopsserver.domain.assistant.entity.RagDocumentEntity;
import org.sscc.ssccopsserver.domain.assistant.repository.RagDocumentRepository;
import org.sscc.ssccopsserver.domain.file.code.FileTargetType;
import org.sscc.ssccopsserver.domain.file.entity.FileReferenceEntity;
import org.sscc.ssccopsserver.domain.file.service.FileDownloader;
import org.sscc.ssccopsserver.domain.file.service.FileReferenceService;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.extern.slf4j.Slf4j;

/*
 * 색인 워커 — `PENDING` 행을 집어 청크를 벡터에 넣는다 (#400 · 기획안 §12.4).
 *
 * ══ 왜 비동기인가 ═══════════════════════════════════════════════
 *
 * **1.2MB PDF 한 건이 184청크라 요청 안에서 임베딩할 크기가 아니다.** 옛 기획안은 «비동기로
 * 가면 «적재 중» 상태가 생기고 그 상태에서 질의가 무엇을 보는지를 또 정해야 한다»는 이유로
 * 동기를 택했는데, **답이 이미 있다** — 검색 조건이 `INDEXED && EFFECTIVE`이고 두 축이 갈라져
 * 있어(#396) «색인 중인 문서를 질의가 보는가»에 «보지 않는다»로 답할 수 있다. 상태가 한
 * 축이었다면 답할 수 없었다.
 *
 * ══ 집는 방법 — 잠그고 다시 본다 ════════════════════════════════
 *
 * 후보는 잠그지 않고 식별자만 훑고(`findIdsByIndexStatus`), 하나씩 `PESSIMISTIC_WRITE`로 다시
 * 읽어 **그때도 `PENDING`인 것만** `INDEXING`으로 전이한다 — 최초 가입자 부트스트랩(#71)의
 * «잠그고 다시 센다»와 같은 두 단계다. 전이는 짧은 트랜잭션에서 커밋되고, **임베딩은 그 밖에서
 * 돈다**: 그 사이 커넥션을 쥐고 있으면 색인 한 건이 Supabase Free의 커넥션 하나를 수십 초씩
 * 붙든다(ssccops#324). 잠금이 지키는 것은 «한 판본을 두 번 색인하지 않는다»이고, 그것은 전이가
 * 커밋된 순간 `INDEXING`이라는 상태 자체가 이어받는다.
 *
 * ══ 동시 실행 1건 ══════════════════════════════════════════════
 *
 * 부르는 쪽(`RagIndexingScheduler`)이 단일 스레드다. **임베딩 쿼터와 DB 커넥션 둘 다를 좁히는
 * 값**이며, 색인이 밀려 «대기»로 줄을 서는 것이 정상 동작이다(§11).
 *
 * ══ ⚠️ 다중 인스턴스에서는 성립하지 않는다 ═══════════════════════
 *
 * **지금은 인스턴스가 하나라 성립한다**(`MemberLinkAttemptLimiter`가 인메모리 카운터로 안고 있는
 * 것과 같은 한계이며, 그 클래스처럼 여기 적어 둔다). 둘이 되면 **기동 복구가 남의 진행 중
 * 작업을 되돌린다** — `INDEXING`은 «누가 하고 있다»를 말할 뿐 «누가»를 말하지 않으므로, 새로
 * 뜬 인스턴스의 복구가 멀쩡히 돌고 있는 다른 인스턴스의 문서를 `PENDING`으로 내리고 그것을 곧
 * 자기가 집는다. 같은 문서가 두 번 임베딩되고 청크가 두 벌 들어간다(옛 청크 삭제와 적재가
 * 서로 엇갈린다). 인스턴스를 늘리려면 **행에 «누가 집었는가»와 «언제까지 유효한가»가 필요하고**
 * 그것은 컬럼 추가이므로, 그때 이 절과 ssccops#324를 함께 다시 본다.
 *
 * ══ 실패는 상태다, 응답이 아니다 ════════════════════════════════
 *
 * 워커는 요청 밖에서 돌아 돌려줄 응답이 없다 — `indx_stts_cd = FAILED` + `fail_rsn_cn`이
 * 오류 코드의 자리를 대신한다(§10). **자동 재시도를 하지 않는다**: 실패의 대부분이 쿼터와 문서
 * 자체이고 자동 재시도는 쿼터 소진을 가속한다. 화면의 «재색인»(#401)이 사람의 판단을 거친
 * 재시도이며, 그것은 상태를 지정하는 조작이 아니라 **`PENDING`으로 다시 줄을 세우는** 조작이다
 * (`requeueIndexing`) — 워커가 그 상태만 집으므로 재색인 전용 경로를 만들면 색인 로직이 두
 * 벌이 된다.
 */
@Slf4j
@Component
public class RagIndexingWorker {

    /*
     * **활성 청크 총량 상한**(기획안 §8.2). 옛 안이 «다시 볼 조건»으로만 적어 둔 숫자를 코드가
     * 거는 값으로 바꾼 것은, 업로드가 생기며 «코퍼스 크기는 고정»이라는 전제가 깨졌기 때문이다.
     *
     * 3,000은 임베딩 약 14MB이고 인덱스 없이 순차 스캔이 여전히 빠른 구간의 끝이다(ADR-0028).
     * **옛 판본·삭제분은 세지 않으므로 여기 닿았다는 것은 실제로 문서가 늘었다는 뜻이다.**
     */
    static final int MAX_ACTIVE_CHUNKS = 3_000;

    /** `fail_rsn_cn`에 담는 사유의 상한. 사람이 목록에서 읽는 한 줄이지 스택 덤프가 아니다 */
    static final int MAX_FAILURE_REASON_LENGTH = 500;

    private final RagDocumentRepository ragDocumentRepository;
    private final FileReferenceService fileReferenceService;
    private final FileDownloader fileDownloader;
    private final RegulationParser regulationParser;
    private final GenericTextExtractor genericTextExtractor;
    private final DocumentChunker documentChunker;
    private final AssistantFeature assistantFeature;

    /*
     * **빈이 없을 수 있다.** 청크 저장소는 Gemini 키가 있을 때만 서고(`AssistantConfig`), 없으면
     * 이 서버에는 임베딩을 부를 방법 자체가 없다 — 요청 경로라면 503 `ASSISTANT_UNAVAILABLE`인
     * 상태다. 그때 문서를 `FAILED`로 내리지 않는 것은 **그것이 문서의 잘못이 아니기** 때문이다:
     * 키를 넣으면 그대로 `PENDING`에서 이어 돌고, 내려 두면 운영진이 전부 손으로 재색인해야 한다.
     */
    private final ObjectProvider<RagChunkStore> ragChunkStore;

    private final Clock clock;
    private final TransactionTemplate transaction;

    /**
     * 기동 복구가 «멈춘 것»으로 보는 나이 (#556). 선언과 «왜 그 값인가»는 {@code application.yaml} 한 곳이고 여기에는 기본값을 두지 않는다
     * — 두 벌로 두면 한쪽만 바뀐다(이 레포의 규정 도우미 손잡이 규칙).
     */
    private final Duration stuckAfter;

    public RagIndexingWorker(
            RagDocumentRepository ragDocumentRepository,
            FileReferenceService fileReferenceService,
            FileDownloader fileDownloader,
            RegulationParser regulationParser,
            GenericTextExtractor genericTextExtractor,
            DocumentChunker documentChunker,
            AssistantFeature assistantFeature,
            ObjectProvider<RagChunkStore> ragChunkStore,
            Clock clock,
            PlatformTransactionManager transactionManager,
            @Value("${ssccops.assistant.indexing.stuck-after}") Duration stuckAfter) {

        this.ragDocumentRepository = ragDocumentRepository;
        this.fileReferenceService = fileReferenceService;
        this.fileDownloader = fileDownloader;
        this.regulationParser = regulationParser;
        this.genericTextExtractor = genericTextExtractor;
        this.documentChunker = documentChunker;
        this.assistantFeature = assistantFeature;
        this.ragChunkStore = ragChunkStore;
        this.clock = clock;
        this.stuckAfter = stuckAfter;

        /*
         * **트랜잭션 경계를 코드로 든다.** 이 클래스의 메서드는 요청 밖에서 자기 스레드로
         * 돌고, 한 번의 색인이 «집기 → (긴 임베딩) → 적기»로 **트랜잭션 셋**에 걸친다 —
         * `@Transactional`을 메서드에 걸면 그 전부가 한 트랜잭션이 되어 임베딩 내내 커넥션을
         * 쥔다. 같은 빈의 메서드를 나눠 부르는 길도 프록시를 지나지 않아 성립하지 않는다
         * (`ProposalFormSeeder`가 같은 이유로 `TransactionTemplate`을 쓴다).
         */
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * 기동 복구 — {@code INDEXING}에 멈춘 행을 {@code PENDING}으로 되돌린다 (기획안 §12.4).
     *
     * <p><b>Supabase Free의 일시정지와 배포 재시작이 실제 원인이다.</b> 색인 중이던 프로세스가 사라지면 그 행을 다시 집을 사람이 없어 영영 «색인
     * 중»으로 남는다.
     *
     * <p>되돌리는 것이 <b>재색인과 같은 메서드</b>({@link RagDocumentEntity#requeueIndexing()})인 것은 결과가 같기 때문이다 —
     * 나누면 복구 경로만 다른 규칙을 갖게 된다.
     *
     * <p><b>나이 조건이 걸려 있다</b> (#556 · ssccops#501). 그전에는 조건이 없어 {@code INDEXING}에 있는 행을 전부 되돌렸고, 그것이
     * «인스턴스는 하나»라는 전제 위에 서 있었다. 배포 중에는 컨테이너가 둘이므로(Coolify가 새 것을 healthy로 만든 뒤 옛 것을 내린다) 새로 뜬 쪽의 기동
     * 복구가 <b>남의 진행 중 작업을 되돌렸다</b> — 결과는 고아 청크와 같은 문서의 재임베딩이고, «드물어 감수하는 값»이 실제로는 <b>배포마다</b>였다.
     *
     * <p>지금은 {@code indexStartedAt}이 {@code stuck-after}(기본 10분) 이전인 행만 되돌린다. 폴링이 10초이고 색인 한 건이 «수십
     * 초»라 정상 진행 중인 작업은 그 창을 넘지 않는다.
     *
     * @return 되돌린 행 수
     */
    public int recoverStuckIndexing() {
        if (!assistantFeature.isEnabled()) {
            return 0;
        }

        List<Long> stuck =
                ragDocumentRepository.findIdsStuckInStatusSince(
                        RagIndexStatus.INDEXING, clock.instant().minus(stuckAfter));
        int requeued = 0;
        for (Long id : stuck) {
            if (Boolean.TRUE.equals(transaction.execute(status -> requeue(id)))) {
                requeued++;
            }
        }
        if (requeued > 0) {
            log.warn("색인 중에 멈춰 있던 규정 문서 {}건을 대기로 되돌렸다 — 이전 프로세스가 색인 도중 종료됐다는 뜻이다", requeued);
        }
        return requeued;
    }

    /**
     * 대기열을 한 바퀴 비운다 — {@code PENDING}을 순서대로 집어 색인한다.
     *
     * <p>후보 목록을 <b>시작할 때 한 번만</b> 뜬다. 도는 동안 새로 올라온 문서는 다음 바퀴가 집으며, 그 편이 «한 바퀴가 끝나지 않는» 상태를 만들지 않는다
     * — 업로드가 계속되는 동안 이 메서드가 돌아오지 않으면 기동 복구도 종료도 그만큼 밀린다.
     *
     * @return 색인에 성공한 문서 수
     */
    public int drainQueue() {
        if (!assistantFeature.isEnabled()) {
            return 0;
        }

        RagChunkStore chunkStore = ragChunkStore.getIfAvailable();
        if (chunkStore == null) {
            /*
             * 키가 없어 배선이 서지 않은 상태다. 대기열은 그대로 두고 아무것도 하지 않는다 —
             * 로그를 매 바퀴 남기지 않는 것은 폴링 주기마다 같은 줄이 쌓이기 때문이고, 이 상태는
             * 이미 기동 로그 한 줄(`GeminiWiringEnvironmentPostProcessor`)이 말하고 있다.
             */
            return 0;
        }

        int indexed = 0;
        for (Long id : ragDocumentRepository.findIdsByIndexStatus(RagIndexStatus.PENDING)) {
            Claimed claimed = transaction.execute(status -> claim(id));
            if (claimed == null) {
                // 그 사이 다른 경로가 집었거나 상태가 바뀌었다. 잠그고 다시 본 덕에 알 수 있다
                continue;
            }
            if (index(claimed, chunkStore)) {
                indexed++;
            }
        }
        return indexed;
    }

    /*
     * 잠그고 다시 본 뒤 `INDEXING`으로 전이하고, 색인에 필요한 값만 떼어 낸다.
     *
     * **엔티티를 들고 나가지 않는다.** 트랜잭션이 끝나면 준영속이 되어 지연 로딩이 터지고, 무엇보다
     * 긴 임베딩 뒤에 그 객체로 상태를 적으면 그동안 바뀐 값을 덮어쓴다 — 적을 때 다시 잠그고 읽는다.
     */
    private Claimed claim(Long id) {
        RagDocumentEntity document = ragDocumentRepository.findByIdForUpdate(id).orElse(null);
        if (document == null || document.getIndexStatus() != RagIndexStatus.PENDING) {
            return null;
        }
        document.startIndexing(clock.instant());

        String objectKey =
                fileReferenceService
                        .findByTarget(FileTargetType.RAG_DOCUMENT, id)
                        .map(FileReferenceEntity::objectKey)
                        .orElse(null);

        return new Claimed(
                id,
                document.getType(),
                document.getName(),
                document.getApplyStatus(),
                document.getOriginalFileName(),
                objectKey);
    }

    /*
     * 한 판본을 색인한다. **트랜잭션 밖이다** — 여기서 일어나는 일(R2 읽기 · 파싱 · 임베딩)이
     * 오래 걸리는 전부이고, 그 사이 커넥션을 쥐지 않는 것이 동시 실행 1건의 대가를 갚는 방법이다.
     */
    private boolean index(Claimed claimed, RagChunkStore chunkStore) {
        Instant startedAt = clock.instant();
        try {
            byte[] content = download(claimed);
            List<Document> chunks = chunk(claimed, content);

            if (chunks.isEmpty()) {
                // 파서가 통과시킨 빈 문서. «색인 완료인데 무엇을 물어도 답하지 못하는 행»을 두지 않는다
                throw new GeneralException(
                        AssistantErrorCode.RAG_DOCUMENT_PARSE_FAILED, "문서에서 색인할 내용을 찾지 못했습니다.");
            }
            requireWithinChunkBudget(chunks.size());

            /*
             * **재색인은 새 청크를 넣기 직전에 옛 청크를 지운다.** 순서를 뒤집으면 중간에
             * 실패했을 때 같은 조가 두 번 검색된다. 첫 색인에는 지울 것이 없고 그것은 실패가
             * 아니다(`RagChunkStore.deleteByRagDocumentId`).
             *
             * 여기까지 오지 못하고 실패하면 **옛 청크가 그대로 남는다** — 그 청크는 직전에
             * 성공한 색인의 결과이고 같은 판본에서 나온 것이므로, 지우는 쪽이 «재색인을 눌렀더니
             * 답이 사라졌다»가 된다.
             */
            chunkStore.deleteByRagDocumentId(claimed.id());
            chunkStore.add(chunks);

            transaction.executeWithoutResult(status -> complete(claimed.id(), chunks.size()));

            /*
             * 걸린 시간을 남긴다 — `indx_bgng_dt`·`indx_end_dt`와 같은 이유다(기획안 §12.4).
             * **실측 없이 배치 크기를 조정할 수 없다.**
             */
            log.info(
                    "규정 문서 색인 완료 — ragDocId={} 유형={} 청크={} 소요={}ms",
                    claimed.id(),
                    claimed.type(),
                    chunks.size(),
                    Duration.between(startedAt, clock.instant()).toMillis());
            return true;

        } catch (RuntimeException exception) {
            String reason = reasonOf(exception);
            transaction.executeWithoutResult(status -> fail(claimed.id(), reason));
            log.error("규정 문서 색인 실패 — ragDocId={} 사유={}", claimed.id(), reason, exception);
            return false;
        }
    }

    /*
     * **재색인의 재료는 언제나 R2의 원본이다**(#399의 계약). 업로드가 만든 파싱 결과를 넘겨받지
     * 않는 것은 파서 규칙이 바뀐 뒤의 재색인이 그때의 청크를 되살리면 안 되기 때문이다.
     */
    private byte[] download(Claimed claimed) {
        if (claimed.objectKey() == null) {
            throw new GeneralException(
                    AssistantErrorCode.RAG_DOCUMENT_PARSE_FAILED,
                    "원본 파일을 찾지 못했습니다. 문서를 지우고 다시 올려 주세요.");
        }
        return fileDownloader.download(claimed.objectKey());
    }

    /*
     * 유형이 파서를 고르고 `DocumentChunker`가 둘을 같은 `Document[]`로 내보낸다(#398) — 그래서
     * 이 클래스는 청킹 규칙이 둘이라는 사실을 알 필요가 없다.
     *
     * **판본의 값(`ragDocId`·`applyStatus`)을 찍는 자리가 여기다.** 청크가 `Document`를 스스로
     * 만들지 않는 이유가 그것이며(#397), 빠뜨리면 아무도 지울 수 없는 고아 청크가 된다.
     * `applyStatus`와 표시명은 집은 시점의 값이고, 뒤에 바뀌면 재색인이 다시 찍는다.
     */
    private List<Document> chunk(Claimed claimed, byte[] content) {
        if (claimed.type() == RagDocumentType.STRUCTURED) {
            return documentChunker.documents(
                    regulationParser.parse(content), claimed.id(), claimed.applyStatus());
        }
        return documentChunker.documents(
                genericTextExtractor.extract(content, claimed.originalFileName()),
                claimed.name(),
                claimed.id(),
                claimed.applyStatus());
    }

    /*
     * 상한을 **적재 직전에** 본다. 세는 시점이 «색인 완료»인 것은 그때라야 청크 수가 정해지기
     * 때문이고(§8.2), 넣기 전에 끊는 것은 넘길 요청이라면 임베딩 수백 번을 치르기 전에 끊는 편이
     * 상한을 둔 목적(자원 보호)에 맞기 때문이다 — 업로드가 일 10회 한도를 파싱 앞에서 보는 것과
     * 같은 순서다(#399).
     *
     * 지금 집은 판본은 `INDEXING`이라 합계에 들어가지 않는다. 그래서 재색인이 자기 옛 청크 수를
     * 이중으로 세지 않는다.
     */
    private void requireWithinChunkBudget(int chunkCount) {
        long active = ragDocumentRepository.sumActiveChunkCount().orElse(0L);
        if (active + chunkCount > MAX_ACTIVE_CHUNKS) {
            throw new GeneralException(
                    AssistantErrorCode.RAG_DOCUMENT_LIMIT_EXCEEDED,
                    "코퍼스가 담을 수 있는 청크는 %d개입니다. 지금 %d개이고 이 문서가 %d개를 더합니다 — 쓰지 않는 문서를 지운 뒤 재색인해 주세요."
                            .formatted(MAX_ACTIVE_CHUNKS, active, chunkCount));
        }
    }

    /*
     * 상태를 적는 세 자리는 전부 **다시 잠그고 다시 본다.** 임베딩이 도는 동안 사람이 재색인을
     * 눌렀거나 다른 경로가 상태를 바꿨을 수 있고, 그때 전이가 성립하지 않으면 적지 않는 것이
     * 맞다 — 엔티티의 전이표가 그 판정을 갖는다(`RagIndexStatus.canTransitionTo`).
     */
    private boolean requeue(Long id) {
        return transitionFrom(
                id, RagIndexStatus.INDEXING, RagDocumentEntity::requeueIndexing, "대기로 되돌리기");
    }

    private void complete(Long id, int chunkCount) {
        transitionFrom(
                id,
                RagIndexStatus.INDEXING,
                document -> document.completeIndexing(chunkCount, clock.instant()),
                "색인 완료");
    }

    private void fail(Long id, String reason) {
        transitionFrom(
                id,
                RagIndexStatus.INDEXING,
                document -> document.failIndexing(reason, clock.instant()),
                "색인 실패");
    }

    private boolean transitionFrom(
            Long id, RagIndexStatus expected, Consumer<RagDocumentEntity> change, String what) {

        Optional<RagDocumentEntity> found = ragDocumentRepository.findByIdForUpdate(id);
        if (found.isEmpty()) {
            // 색인 중에 하드 삭제됐다(ADR-0029). 청크는 다음 재색인·삭제가 지운다
            log.warn("규정 문서를 찾지 못해 «{}»를 적지 못했다 — ragDocId={}", what, id);
            return false;
        }
        RagDocumentEntity document = found.get();
        if (document.getIndexStatus() != expected) {
            log.warn(
                    "규정 문서의 색인 상태가 그 사이 {}로 바뀌어 «{}»를 적지 않는다 — ragDocId={}",
                    document.getIndexStatus(),
                    what,
                    id);
            return false;
        }
        change.accept(document);
        return true;
    }

    /*
     * 실패 사유는 **운영진이 목록에서 읽는 한 줄**이다(§13.2). `GeneralException`이면 우리가 쓴
     * 문장이 그대로 쓸모 있고(파싱 몇째 줄·상한 초과), 그렇지 않으면 예외 종류라도 남겨야 «쿼터
     * 소진»과 «원본 없음»을 가를 수 있다. 스택은 로그의 몫이고 여기 담지 않는다.
     */
    private static String reasonOf(RuntimeException exception) {
        String reason =
                exception instanceof GeneralException general
                        ? "[%s] %s"
                                .formatted(general.getErrorCode().getCode(), general.getMessage())
                        : "[%s] %s"
                                .formatted(
                                        exception.getClass().getSimpleName(),
                                        exception.getMessage() == null
                                                ? "알 수 없는 오류"
                                                : exception.getMessage());

        return reason.length() <= MAX_FAILURE_REASON_LENGTH
                ? reason
                : reason.substring(0, MAX_FAILURE_REASON_LENGTH) + "…";
    }

    /** 집은 판본에서 색인에 필요한 값만. 엔티티가 아닌 이유는 {@link #claim}의 주석에 있다 */
    private record Claimed(
            Long id,
            RagDocumentType type,
            String name,
            RagApplyStatus applyStatus,
            String originalFileName,
            String objectKey) {}
}
