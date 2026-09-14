package org.sscc.ssccopsserver.domain.assistant.service;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.sscc.ssccopsserver.domain.assistant.code.RagApplyStatus;
import org.sscc.ssccopsserver.domain.assistant.code.RagDocumentFormat;
import org.sscc.ssccopsserver.domain.assistant.code.RagDocumentType;
import org.sscc.ssccopsserver.domain.assistant.code.RagIndexStatus;
import org.sscc.ssccopsserver.domain.assistant.code.error.AssistantErrorCode;
import org.sscc.ssccopsserver.domain.assistant.dto.RagCorpusSummaryResponse;
import org.sscc.ssccopsserver.domain.assistant.dto.RagDocumentApplyStatusUpdateRequest;
import org.sscc.ssccopsserver.domain.assistant.dto.RagDocumentArticleResponse;
import org.sscc.ssccopsserver.domain.assistant.dto.RagDocumentDetailResponse;
import org.sscc.ssccopsserver.domain.assistant.dto.RagDocumentListResponse;
import org.sscc.ssccopsserver.domain.assistant.dto.RagDocumentResponse;
import org.sscc.ssccopsserver.domain.assistant.dto.RegulationChapter;
import org.sscc.ssccopsserver.domain.assistant.dto.RegulationDocument;
import org.sscc.ssccopsserver.domain.assistant.entity.RagDocumentEntity;
import org.sscc.ssccopsserver.domain.assistant.repository.RagDocumentRepository;
import org.sscc.ssccopsserver.domain.file.code.FileTargetType;
import org.sscc.ssccopsserver.domain.file.entity.FileReferenceEntity;
import org.sscc.ssccopsserver.domain.file.service.FileDownloader;
import org.sscc.ssccopsserver.domain.file.service.FilePresigner;
import org.sscc.ssccopsserver.domain.file.service.FileReferenceService;
import org.sscc.ssccopsserver.domain.file.service.FileUploader;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.code.error.CommonErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/*
 * 규정 문서 코퍼스 — 업로드(#399) · 목록·상세·적용 전환·재색인·하드 삭제(#401).
 * 기획안 §5.1 · §5.5 · §5.6 · §9 · §11.
 *
 * ══ 순서가 계약이다 — 파싱 → 행 → R2 PUT ═════════════════════════
 *
 * **파싱이 R2 PUT보다 먼저인 것이 요점이다.** 반대로 두면 파싱에 실패한 파일의 오브젝트가
 * 고아로 남는다 — **되돌릴 수 없는 쪽을 뒤로 미루는 것**이 `FileEraser`(#234)·`FileCopier`와
 * 같은 규칙이다. 파싱을 워커로 미루지 않는 것은, 그러면 `.md` 계약 위반이 «색인 실패»로만
 * 드러나고 화면에 미리보기가 없어 운영진이 무엇이 틀렸는지 즉시 알 수 없기 때문이다(§2).
 * 오래 걸리는 것은 임베딩뿐이고 그것만 뒤로 뺀다 — **이 메서드는 임베딩을 부르지 않는다.**
 *
 * **행 저장이 PUT보다 앞인 것은 키에 `ragDocId`가 들어가기 때문이다.** 계약이 말하는
 * «실패했을 때 아무것도 남지 않는다»는 그대로다 — 한 트랜잭션이라 PUT이 실패하면 행도 함께
 * 롤백되고(`FileUploader`가 커밋 뒤로 미루지 않는 이유), 파싱이 실패하면 그 전이라 행도 PUT도
 * 없다. 반대로 키에서 식별자를 빼면 «어느 문서의 원본인가»를 `file_rfrnc` 행으로만 알 수 있어
 * 버킷만 보고는 아무것도 찾을 수 없다.
 *
 * ══ 파싱 결과를 쓰지 않고 버린다 ═══════════════════════════════
 *
 * 여기서 파싱은 **검증**이다. 청크를 만들어 두었다가 워커에 넘기지 않는 것은 재색인의 재료가
 * 언제나 R2의 원본이기 때문이며(파서 규칙이 바뀌면 그때 다시 읽어야 한다), 한 번 더 읽는 비용은
 * 색인 한 번에 붙는 임베딩 수백 번 옆에서 보이지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagDocumentServiceImpl implements RagDocumentService {

    /*
     * 업로드 상한 10MB. **서블릿 설정(`spring.servlet.multipart.max-file-size` 16MB)이 아니라
     * 여기서 끊는다** — 서블릿 계층이 먼저 걸러 버리면 도메인 오류 코드가 붙지 않은 응답이 나가고
     * 화면이 무엇이 잘못됐는지 안내하지 못한다(#84가 CSV 5MB에서 쓴 두 겹 그대로).
     */
    private static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024;

    /*
     * 적재 레이트 리밋 — **회원당 일 10회**(기획안 §11).
     *
     * 질의 한도와 따로 두는 것은 **적재 한 건이 나중에 임베딩을 수백 번 부르기 때문**이다
     * (1.2MB PDF 한 건이 184청크). 무료 쿼터는 API 키 단위의 공유 자원이라 한 사람이 태우면
     * 모두가 답을 못 받는다.
     */
    private static final int MAX_UPLOADS_PER_DAY = 10;

    /*
     * `doc_cd`의 모양. **어휘는 강제하지 않는다** — `REGULATION`·`SCHOOL_RULE`은 운영 규칙이고
     * 표준코드 그룹으로 등재하지 않았다(ssccops#325 · 엔티티 주석). 여기서 보는 것은 모양뿐이며,
     * 그 이유는 이 값이 판본을 묶는 열쇠라 **눈으로 같아 보이는 두 값이 다른 문서가 되면 안 되기**
     * 때문이다. 공백·한글·하이픈을 섞어 받으면 `회칙`과 `회칙 `이 각자 1판본으로 자란다.
     */
    private static final Pattern DOCUMENT_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,19}");

    /** `doc_nm`·`orgnl_file_nm` 컬럼 길이. 조용히 자르지 않고 400으로 돌려보낸다 */
    private static final int MAX_NAME_LENGTH = 200;

    private final AssistantFeature assistantFeature;
    private final RegulationParser regulationParser;
    private final GenericTextExtractor genericTextExtractor;
    private final RagDocumentRepository ragDocumentRepository;
    private final RagChunkEraser ragChunkEraser;
    private final FileReferenceService fileReferenceService;
    private final FileUploader fileUploader;
    private final FileDownloader fileDownloader;
    private final FilePresigner filePresigner;
    private final Clock clock;

    @Override
    @Transactional
    public RagDocumentResponse upload(
            MultipartFile file, String documentCode, String name, MemberEntity registrant) {

        assistantFeature.requireEnabled();

        String code = normalizeDocumentCode(documentCode);
        String originalFileName = requireFileName(file);
        RagDocumentFormat format = RagDocumentFormat.fromFileName(originalFileName);
        byte[] content = readContent(file);

        /*
         * 한도를 파싱 앞에서 본다 — Tika 파싱이 이 메서드에서 가장 비싼 일이고, 막을 요청이라면
         * 그 비용을 치르기 전에 막는 편이 한도를 둔 목적(자원 보호)에 맞다.
         */
        requireWithinDailyQuota(registrant);

        parse(format, content, originalFileName);

        short version =
                ragDocumentRepository
                        .findMaxVersion(code)
                        .map(previous -> (short) (previous + 1))
                        .orElse(RagDocumentEntity.FIRST_VERSION);

        /*
         * **식별자를 먼저 받는다** — 키가 `rag-documents/{ragDocId}/…`라서다. 같은 `doc_cd`에
         * 동시 업로드가 겹치면 둘이 같은 번호를 집어 `uk_rag_doc_doc_cd_ver`에 걸리는데, 그때는
         * 둘째 요청이 500으로 떨어지고 아무것도 남지 않는다 — 운영진이 다시 올리면 된다. 잠금을
         * 걸지 않은 것은 첫 판본에는 잠글 행이 없어(같은 이유로 #401의 시행본 판정도 그렇다)
         * 반쪽짜리 방어가 되고, 일 10회 한도 아래에서 실제로 겹칠 일이 없기 때문이다.
         */
        RagDocumentEntity document =
                ragDocumentRepository.saveAndFlush(
                        RagDocumentEntity.register(
                                code,
                                resolveName(name, originalFileName, format),
                                format.getDocumentType(),
                                version,
                                originalFileName,
                                content.length,
                                registrant));

        /*
         * 키를 조립하는 자리는 여기 한 곳이다. **원본 파일명을 키에 쓰지 않는다** — 같은 버킷에
         * 얼굴이 찍힌 출석 인증사진이 있으므로(ssccops#156) `../`가 낀 파일명이 키가 되면 그것이
         * 곧 남의 사진이다. 확장자도 파일명이 아니라 형식 표의 값을 붙인다.
         */
        String objectKey =
                FileTargetType.RAG_DOCUMENT.getObjectKeyPrefix()
                        + "%d/%s%s"
                                .formatted(
                                        document.getId(), UUID.randomUUID(), format.getExtension());

        fileUploader.upload(objectKey, content, format.getContentType());
        fileReferenceService.upsert(FileTargetType.RAG_DOCUMENT, document.getId(), objectKey);

        log.info(
                "규정 문서 업로드 — ragDocId={} docCd={} 판본={} 형식={} 크기={}바이트",
                document.getId(),
                code,
                version,
                format,
                content.length);

        return RagDocumentResponse.from(document);
    }

    /*
     * ══ 목록 — 요약을 같은 응답에 싣는다 ═══════════════════════════
     *
     * 나누면 두 요청 사이에 색인이 끝나 **카드와 표가 다른 시점을 가리킨다**(#37 · §13.2).
     * 검색어가 있어도 요약은 코퍼스 전체이며 그 근거는 `RagCorpusSummaryResponse`에 있다.
     *
     * **총 청크는 워커가 상한을 판정할 때 보는 수와 같은 것이어야 한다** — `sumActiveChunkCount`를
     * 그대로 쓰는 이유다(#400). 여기서 «전부 더하기»로 따로 세면 화면의 숫자와 429·409의 근거가
     * 갈린다.
     */
    @Override
    @Transactional(readOnly = true)
    public RagDocumentListResponse list(String keyword) {
        assistantFeature.requireEnabled();

        String name = keyword == null ? "" : keyword.trim();
        List<RagDocumentEntity> documents =
                name.isEmpty()
                        ? ragDocumentRepository.findAllByOrderByIdDesc()
                        : ragDocumentRepository.findAllByNameContainingIgnoreCaseOrderByIdDesc(
                                name);

        RagCorpusSummaryResponse summary =
                new RagCorpusSummaryResponse(
                        ragDocumentRepository.count(),
                        ragDocumentRepository.countByIndexStatus(RagIndexStatus.INDEXED),
                        ragDocumentRepository.sumActiveChunkCount().orElse(0L));

        return new RagDocumentListResponse(
                summary, documents.stream().map(RagDocumentResponse::from).toList());
    }

    @Override
    @Transactional(readOnly = true)
    public RagDocumentDetailResponse detail(Long ragDocId) {
        assistantFeature.requireEnabled();

        RagDocumentEntity document = read(ragDocId);
        String objectKey =
                fileReferenceService
                        .findByTarget(FileTargetType.RAG_DOCUMENT, ragDocId)
                        .map(FileReferenceEntity::objectKey)
                        .orElse(null);

        /*
         * **서명만 받아 온다** — 「누가 볼 수 있는가」는 클래스 레벨 `@RequireAuthority`가 이미
         * 끝냈고 `FilePresigner`는 그것을 다시 묻지 않는다(#220). 읽기 TTL 15분을 이 화면 전용으로
         * 따로 두지 않은 것은 용도가 «원본을 확인하러 한 번 연다»라 그 값이 넉넉하고, 만료되면
         * 상세를 다시 부르면 되기 때문이다 — 그래서 남은 시간을 함께 싣는다.
         *
         * 참조가 없으면 null이다. 업로드가 중간에 실패한 행이 그럴 수 있고, 그 상태는 색인도
         * «원본 파일을 찾지 못했습니다»로 실패한다(#400) — 화면이 할 일이 «지우고 다시 올린다»다.
         */
        String downloadUrl = objectKey == null ? null : filePresigner.presignGet(objectKey);

        return new RagDocumentDetailResponse(
                RagDocumentResponse.from(document),
                document.getRegistrant().getId(),
                document.getRegistrant().getName(),
                document.getIndexStartedAt(),
                document.getIndexEndedAt(),
                document.getUpdatedAt(),
                downloadUrl,
                downloadUrl == null ? null : filePresigner.viewUrlTtlSeconds(),
                articlesOf(document, objectKey));
    }

    /*
     * ══ 적용 전환 — 시행본은 문서당 하나다 ════════════════════════
     *
     * **기존 시행본을 같은 트랜잭션에서 내린다**(§5.5 · 대표 역할 `rprs_role_yn`이 회원당 1건인
     * 것과 같은 모양). 규칙은 두 겹인데 — PostgreSQL의 부분 유니크 인덱스
     * (`uk_rag_doc_effective`)와 여기의 판정 — **H2에는 그 인덱스가 없어 이 판정이 유일한
     * 방어선인 환경이 있다**(#143의 초안 1건 제약과 같은 자리). 그래서 읽는 것이 아니라
     * `findByDocumentCodeAndApplyStatusForUpdate`로 **잠그고** 읽는다: 잠그지 않으면 동시 요청
     * 둘이 모두 «시행 중인 판본 없음»을 보고 둘 다 올라가며, 테스트는 그것을 재현하지 못한다.
     *
     * 내리는 것이 올리는 것보다 먼저이고 그 사이에 `flush`가 있다. JPA가 두 UPDATE를 커밋
     * 시점에 내보내는 순서는 보장되지 않는데, 승격이 먼저 나가면 **부분 유니크 인덱스가 그
     * 찰나에 시행본 둘을 본다.**
     *
     * 전이가 성립하지 않으면(색인 전 · 이미 종착점) 엔티티가 던지고 **트랜잭션이 통째로 롤백돼
     * 내려간 판본도 되돌아온다.** 청크 삭제를 커밋 뒤로 미룬 것이 그래서 맞다.
     */
    @Override
    @Transactional
    public RagDocumentResponse changeApplyStatus(
            Long ragDocId, RagDocumentApplyStatusUpdateRequest request) {

        assistantFeature.requireEnabled();

        RagDocumentEntity document = lock(ragDocId);
        RagApplyStatus next = request.applyStatus();

        Long supersededId =
                next == RagApplyStatus.EFFECTIVE ? supersedeCurrentVersion(document) : null;

        document.changeApplyStatus(next, resolveEffectiveFrom(request.effectiveFrom()));

        if (next == RagApplyStatus.SUPERSEDED) {
            supersededId = document.getId();
        }
        if (supersededId != null) {
            /*
             * **내려간 판본의 청크는 사라진다.** 검색 조건이 `INDEXED && EFFECTIVE`라 다시 볼
             * 경로가 없고, 활성 청크 합계가 `SUPERSEDED`를 빼고 세므로(§8.2) 지우지 않으면
             * 상한 3,000이 실제 저장량보다 낮은 수를 보고 판정한다. 커밋 뒤인 이유는
             * `RagChunkEraser`에 있다.
             */
            ragChunkEraser.eraseAfterCommit(supersededId);
        }

        log.info("규정 문서 적용 상태 전환 — ragDocId={} → {} (내려간 판본={})", ragDocId, next, supersededId);

        return RagDocumentResponse.from(document);
    }

    /*
     * 재색인은 **`PENDING`으로 다시 줄을 세우는 것뿐이다**(#400). 잠그고 부르는 것은 워커가 지금
     * 이 행을 집는 중일 수 있기 때문이며, 색인 중에 눌린 재색인은 `INDEXING → PENDING`으로
     * 성립한다 — 그 뒤 워커가 결과를 적으려고 다시 잠그면 상태가 `PENDING`이라 적지 않고 넘어간다.
     *
     * 이미 `PENDING`인 행에 누르면 400이다(전이표). 화면은 그 상태에서 버튼을 내리므로 정상
     * 경로에서는 오지 않는 요청이고, 그 판정도 여기가 아니라 전이표 한 곳에 있다.
     */
    @Override
    @Transactional
    public RagDocumentResponse reindex(Long ragDocId) {
        assistantFeature.requireEnabled();

        RagDocumentEntity document = lock(ragDocId);
        document.requeueIndexing();

        log.info("규정 문서 재색인 요청 — ragDocId={} 대기열로 되돌렸다", ragDocId);
        return RagDocumentResponse.from(document);
    }

    /*
     * ══ 하드 삭제 — 행 · 청크 · R2 오브젝트 ═══════════════════════
     *
     * **소프트 삭제가 아니다**(ADR-0029). 폼(#329)·행사(#347)와 갈리는 것은 그쪽이 «치우기»이고
     * 이쪽은 «잘못 올린 파일을 없었던 것으로 만들기»이기 때문이며, 목록에 영구히 남기면 이 표가
     * «지금 도우미가 참조하는 문서»라는 뜻을 잃는다. 남길 값이 있는 옛 판본은 `SUPERSEDED`가 이미
     * 맡는다. **그래서 되살리기가 없다** — 되돌리려면 같은 파일을 새 판본으로 올린다.
     *
     * **되돌릴 수 없는 둘(R2 오브젝트·청크)이 커밋 뒤다.** 안에서 지우면 롤백된 삭제 뒤에 «행은
     * 있는데 파일이 없는» 조합이 남는다(#234의 규칙 그대로).
     *
     * 잠그고 읽는 것은 워커가 이 판본을 색인하는 중일 수 있어서다. 그래도 «임베딩이 끝난 뒤
     * 들어오는 청크»를 완전히 막지는 못한다 — 워커는 잠금 밖에서 적재하고, 그때는 행이 없어
     * 상태를 적지 못한 채(#400의 `transitionFrom`) 청크만 남는다. 그 청크는 검색되지 않지만
     * 지울 주체도 없다. 인스턴스가 하나이고 색인 중 삭제가 드물어 감수하는 값이며, 늘리려면
     * 워커에 «내가 집었다»를 적는 컬럼이 필요하다(#400의 다중 인스턴스 항목과 같은 자리).
     */
    @Override
    @Transactional
    public void delete(Long ragDocId) {
        assistantFeature.requireEnabled();

        RagDocumentEntity document = lock(ragDocId);

        fileReferenceService.deleteByTarget(FileTargetType.RAG_DOCUMENT, ragDocId);
        ragDocumentRepository.delete(document);
        ragChunkEraser.eraseAfterCommit(ragDocId);

        log.info(
                "규정 문서 하드 삭제 — ragDocId={} docCd={} 판본={}",
                ragDocId,
                document.getDocumentCode(),
                document.getVersion());
    }

    /*
     * 같은 `doc_cd`의 시행본을 내린다. **자기 자신이면 내리지 않는다** — 이미 `EFFECTIVE`인 행에
     * 다시 `EFFECTIVE`를 요청한 경우이고, 그때 내려 버리면 전이표가 거절해야 할 요청이 «내렸다가
     * 올리는» 성공으로 바뀐다.
     *
     * @return 내려간 판본의 식별자. 없었으면 null
     */
    private Long supersedeCurrentVersion(RagDocumentEntity promoted) {
        return ragDocumentRepository
                .findByDocumentCodeAndApplyStatusForUpdate(
                        promoted.getDocumentCode(), RagApplyStatus.EFFECTIVE)
                .filter(current -> !current.getId().equals(promoted.getId()))
                .map(
                        current -> {
                            current.supersede();
                            // 승격보다 먼저 DB에 닿아야 한다 — 부분 유니크 인덱스가 그 찰나를 본다
                            ragDocumentRepository.flush();
                            return current.getId();
                        })
                .orElse(null);
    }

    /*
     * 시행일을 비워 보내면 **오늘**이다(§5.5의 «YYYY-MM-DD 시행 기준» 배지). 비워 두는 쪽을
     * 택하지 않은 것은 그 배지가 답변마다 붙는 값이라 없으면 화면이 그릴 것이 없어지고, «오늘
     * 시행 중으로 올렸다»가 그 판본에 대해 서버가 아는 유일한 사실이기 때문이다. 의결일이 따로
     * 있으면 요청이 그 날짜를 싣는다.
     *
     * `DRAFT`·`SUPERSEDED` 전이에서는 쓰이지 않는다 — 엔티티가 무시한다.
     */
    private LocalDate resolveEffectiveFrom(LocalDate effectiveFrom) {
        return effectiveFrom == null ? LocalDate.now(clock) : effectiveFrom;
    }

    /*
     * 상세의 조 목록 — **원본을 다시 파싱해 만든다.**
     *
     * 업로드가 만든 파싱 결과를 들고 있지 않은 것이 이 도메인의 계약이고(#399 · 재색인의 재료도
     * 언제나 R2의 원본이다), 청크에서 되돌릴 수도 없다(해설을 뺐고 장 헤더를 덧붙였다). `.md`는
     * 10MB 이하의 텍스트이고 파싱에 외부 호출이 없어 이 한 번이 상세 조회에 보이지 않는다 —
     * `GENERIC`은 애초에 조가 없어 읽지도 않는다(#398 · 평문에서 «제○조»를 흉내 내지 않는다).
     *
     * R2 GET이 **조회 트랜잭션 안**에서 일어난다. 색인 워커가 임베딩을 트랜잭션 밖으로 뺀 것과
     * 갈리는데(#400), 그쪽은 한 건이 수십 초라 커넥션 하나를 그만큼 붙들기 때문이고 여기는
     * 작은 파일 하나의 왕복이다. **오래 걸리기 시작하면 이 자리를 먼저 본다** — 커넥션은
     * Supabase Free의 좁은 자원이다(ssccops#324).
     *
     * **실패해도 상세는 뜬다.** 파서 규칙이 바뀌어 옛 판본이 더는 계약을 만족하지 않을 수 있는데,
     * 그때 상세가 통째로 500이면 운영진이 그 문서를 지울 화면조차 열지 못한다 — 조 목록만 비고
     * 나머지(실패 사유 · 원본 다운로드)는 그대로 쓸모 있다.
     */
    private List<RagDocumentArticleResponse> articlesOf(
            RagDocumentEntity document, String objectKey) {

        if (document.getType() != RagDocumentType.STRUCTURED || objectKey == null) {
            return List.of();
        }
        try {
            RegulationDocument parsed = regulationParser.parse(fileDownloader.download(objectKey));
            List<RagDocumentArticleResponse> articles = new ArrayList<>();
            for (RegulationChapter chapter : parsed.chapters()) {
                chapter.articles()
                        .forEach(
                                article ->
                                        articles.add(
                                                RagDocumentArticleResponse.of(
                                                        chapter.title(),
                                                        chapter.supplementary(),
                                                        article)));
            }
            return articles;
        } catch (RuntimeException exception) {
            log.warn(
                    "규정 문서의 조 목록을 만들지 못했다 — ragDocId={} (상세는 그대로 내린다)",
                    document.getId(),
                    exception);
            return List.of();
        }
    }

    /** 없으면 404 — <b>삭제가 하드라 «없음»이 정상 상태다</b>(`AssistantErrorCode.RAG_DOCUMENT_NOT_FOUND`) */
    private RagDocumentEntity read(Long ragDocId) {
        return ragDocumentRepository
                .findById(ragDocId)
                .orElseThrow(() -> new GeneralException(AssistantErrorCode.RAG_DOCUMENT_NOT_FOUND));
    }

    /** 바꾸기 전에 <b>잠그고</b> 읽는다 — 워커가 같은 행을 집는 중일 수 있고, 운영진 둘이 같은 행에 닿을 수 있다 */
    private RagDocumentEntity lock(Long ragDocId) {
        return ragDocumentRepository
                .findByIdForUpdate(ragDocId)
                .orElseThrow(() -> new GeneralException(AssistantErrorCode.RAG_DOCUMENT_NOT_FOUND));
    }

    /*
     * 유형이 파서를 고른다(§5.2). **결과는 버린다** — 여기서 파싱은 검증이고, 색인은 R2의 원본을
     * 다시 읽는다(클래스 주석).
     */
    private void parse(RagDocumentFormat format, byte[] content, String fileName) {
        if (format.getDocumentType() == RagDocumentType.STRUCTURED) {
            // 바이트를 그대로 넘긴다 — UTF-8 디코딩과 BOM 제거는 파서의 계약이고(#400), 여기서
            // 한 번 더 하면 검증과 색인이 같은 파일을 다르게 읽을 자리가 생긴다
            regulationParser.parse(content);
            return;
        }
        genericTextExtractor.extract(content, fileName);
    }

    /*
     * 오늘 올린 건수로 센다 — 하루의 경계는 서비스 표준 시간대(Asia/Seoul)이고 그 값은 주입된
     * `Clock`에서 온다(AP-12 · 테스트가 고정할 수 있는 유일한 방법이기도 하다).
     */
    private void requireWithinDailyQuota(MemberEntity registrant) {
        Instant startOfToday = LocalDate.now(clock).atStartOfDay(clock.getZone()).toInstant();
        long today =
                ragDocumentRepository.countByRegistrantIdAndCreatedAtGreaterThanEqual(
                        registrant.getId(), startOfToday);

        if (today >= MAX_UPLOADS_PER_DAY) {
            throw new GeneralException(
                    AssistantErrorCode.ASSISTANT_RATE_LIMITED,
                    "하루에 올릴 수 있는 문서는 %d건입니다. 내일 다시 올려 주세요.".formatted(MAX_UPLOADS_PER_DAY));
        }
    }

    private static String normalizeDocumentCode(String documentCode) {
        String code = documentCode == null ? "" : documentCode.trim().toUpperCase(Locale.ROOT);
        if (!DOCUMENT_CODE.matcher(code).matches()) {
            throw new GeneralException(
                    CommonErrorCode.VALIDATION_FAILED,
                    "문서 코드(documentCode)는 영문 대문자로 시작하는 20자 이내의 영문·숫자·밑줄이어야 합니다.");
        }
        return code;
    }

    /*
     * 표시명의 기본값은 **파일명에서 확장자를 뗀 것**이다(#399). 운영진이 나중에 화면에서 고치며,
     * 그 값은 `GENERIC` 청크의 맨 앞에 붙으므로(#398) 재색인 때 다시 찍힌다.
     */
    private static String resolveName(
            String name, String originalFileName, RagDocumentFormat format) {

        String trimmed = name == null ? "" : name.trim();
        String resolved =
                trimmed.isEmpty()
                        ? originalFileName.substring(
                                0, originalFileName.length() - format.getExtension().length())
                        : trimmed;

        if (resolved.isBlank() || resolved.length() > MAX_NAME_LENGTH) {
            throw new GeneralException(
                    CommonErrorCode.VALIDATION_FAILED,
                    "문서 표시명(name)은 1자 이상 %d자 이하여야 합니다.".formatted(MAX_NAME_LENGTH));
        }
        return resolved;
    }

    /*
     * 파일명은 형식을 정하는 값이자(§5.2) 그대로 `orgnl_file_nm`에 남는 값이다. 없는 요청은
     * 확장자를 볼 수 없어 형식 판정 자체가 성립하지 않는다.
     */
    private static String requireFileName(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new GeneralException(CommonErrorCode.VALIDATION_FAILED, "업로드할 파일(file)이 없습니다.");
        }
        String fileName = file.getOriginalFilename();
        if (fileName == null || fileName.isBlank() || fileName.length() > MAX_NAME_LENGTH) {
            throw new GeneralException(
                    CommonErrorCode.VALIDATION_FAILED,
                    "파일 이름은 1자 이상 %d자 이하여야 합니다.".formatted(MAX_NAME_LENGTH));
        }
        return fileName;
    }

    /*
     * **신고한 크기가 아니라 실제 바이트로 끊는다.** `MultipartFile.getSize()`는 이미 서블릿이
     * 받아 둔 값이라 거짓일 수 없지만, 어차피 파싱하려면 전부 읽어야 하므로 판정도 읽은 것으로
     * 한다 — 두 값이 갈릴 자리를 만들지 않는다.
     */
    private static byte[] readContent(MultipartFile file) {
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new GeneralException(AssistantErrorCode.RAG_DOCUMENT_TOO_LARGE, tooLarge());
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException exception) {
            log.warn("규정 문서 업로드 본문을 읽지 못했다", exception);
            throw new GeneralException(
                    AssistantErrorCode.RAG_DOCUMENT_PARSE_FAILED, "파일을 읽지 못했습니다. 다시 올려 주세요.");
        }
        if (content.length > MAX_FILE_SIZE_BYTES) {
            throw new GeneralException(AssistantErrorCode.RAG_DOCUMENT_TOO_LARGE, tooLarge());
        }
        return content;
    }

    private static String tooLarge() {
        return "파일은 %dMB까지 올릴 수 있습니다.".formatted(MAX_FILE_SIZE_BYTES / 1024 / 1024);
    }
}
