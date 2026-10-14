package org.sscc.ssccopsserver.domain.form.service;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.file.code.AttachmentFileType;
import org.sscc.ssccopsserver.domain.file.code.FileTargetType;
import org.sscc.ssccopsserver.domain.file.entity.FileReferenceEntity;
import org.sscc.ssccopsserver.domain.file.service.FilePresigner;
import org.sscc.ssccopsserver.domain.file.service.FileReferenceService;
import org.sscc.ssccopsserver.domain.form.code.error.FormErrorCode;
import org.sscc.ssccopsserver.domain.form.dto.ProposalAttachmentListResponse;
import org.sscc.ssccopsserver.domain.form.dto.ProposalAttachmentResponse;
import org.sscc.ssccopsserver.domain.form.dto.ProposalAttachmentUploadRequest;
import org.sscc.ssccopsserver.domain.form.dto.ProposalAttachmentUploadResponse;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;
import org.sscc.ssccopsserver.domain.form.repository.FormRepository;
import org.sscc.ssccopsserver.domain.form.repository.FormResponseHistoryRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;
import org.sscc.ssccopsserver.global.audit.AuditAction;
import org.sscc.ssccopsserver.global.audit.AuditEvent;
import org.sscc.ssccopsserver.global.audit.AuditLog;

import lombok.RequiredArgsConstructor;

/*
 * 기획안 첨부 자료 (server#685 · ssccops#607). 운영 건 첨부(OperationAttachmentServiceImpl · #493)와 같은 흐름 —
 * 발급 → 브라우저가 R2로 PUT → 확정 단계 없이 참조 행이 곧 첨부다. 서버는 PUT을 관측하지 않고, 실패한 PUT의
 * 뒤처리는 웹이 DELETE로 한다.
 *
 * ── 문항이 아니라 응답 행에 붙인다 ─────────────────────────────────────
 * 폼 문항 유형(FILE)으로 넣는 길을 택하지 않았다. 운영 중인 기획안 폼은 문항 구조가 잠겨 있어(409
 * SYSTEM_FORM_QUESTIONS_LOCKED · 시드는 기존 폼을 고치지 않는다 ADR-0005) 보수 마이그레이션이 따로 필요하고,
 * rspns_cn은 문자열뿐이라 검증기·웹 렌더러(www 공개 폼·행사 신청이 같이 쓴다)·편집기 두 벌·응답 표가 전부
 * 바뀐다 — «기획안 하나 때문에 폼 도메인 전체가 바뀐다»는 ADR-0004가 반복 그룹을 기각한 이유 그대로다.
 * 응답 행 id에 붙이면 ProposalFormSeed·SystemFormContract·ProposalResponseParser·이관 코드가 하나도 바뀌지
 * 않고, 자동 저장 PUT이 rspns_cn을 통째로 덮어써도 첨부는 영향을 받지 않는다.
 *
 * ── 승인할 때 복사하지 않는다 ──────────────────────────────────────────
 * 학술 활동은 acdm_actv.form_rspns_id(NOT NULL · UNIQUE)로 원 응답에 닿으므로 그 응답의 첨부를 그대로 읽는다
 * (listOfResponse). 활동 대상(ACADEMIC_PROGRAM)으로 복사하면 승인 트랜잭션(ADR-0002)에 R2 복사라는 실패 지점이
 * 생기고 오브젝트가 두 벌이 된다. 승인된 응답은 바뀌지 않으므로(ADR-0003 · 아래 상태 잠금) 복사할 이유가
 * 없다 — 활동 쪽에서 첨부를 고치는 요구가 생기는 날 다시 본다.
 *
 * ── 기획안에만 연다 ──────────────────────────────────────────────────
 * 판정은 이 클래스의 상수 하나(ATTACHABLE_SYSTEM_FORM_CODE)다. 모든 폼에 열면 접수 중인 아무 폼의 초안에나
 * 회원이 20MB씩 올리는 표면이 생기고, 신청 폼 응답의 첨부를 누가 보는지 규칙이 따로 필요해진다. **쓰기만 이
 * 판정으로 끊는다** — 기획안이 아닌 응답에는 첨부가 생길 길이 없어 읽기는 언제나 빈 목록이고, 읽기까지
 * 거절하면 응답 상세를 그리는 화면이 폼 종류를 먼저 가려야 부를 수 있게 된다.
 *
 * ── 상한 — 형식 셋 · 응답당 합계 20MB · 5개 ──────────────────────────────
 * 운영 건(전체 형식 · 파일당 25MB)과 따로 두는 것은 받는 사람이 다르기 때문이다 — 운영 건은 운영진끼리, 이쪽은
 * 승인 뒤 회원 전체가 내려받는다. 파일당 상한은 두지 않는다(2026-10-14 · 파일당 10MB → 합계 20MB): 여러 개를
 * 올릴 수 있으니 기획안 하나가 차지하는 양을 합으로 묶는 것이 요구이고, 파일당 상한을 따로 두면 «10MB 둘»은
 * 되는데 «15MB 하나»는 안 되는 경계가 생긴다. 그래서 presignPut에 넘기는 상한이 **남은 용량**이다.
 *   - 개수·합계는 DB 제약으로 걸 수 없어 **응답 행을 잠근 채** 센다(lockByIdAndFormAndMember). 잠그지 않으면
 *     동시 발급 둘이 각자 남은 용량을 보고 함께 통과한다.
 *   - 합은 발급 때 신고한 크기(file_size)로 센다. 서명 PUT이 Content-Length를 고정하므로 실제 오브젝트가
 *     신고값보다 클 수 없다 — R2에 HEAD를 쳐서 다시 잴 이유가 없다. PUT에 실패해 오브젝트가 없는 참조도
 *     지워질 때까지 용량을 차지한다(웹이 실패하면 DELETE하고, 탭이 닫혀 남은 것은 작성자가 목록에서 지운다).
 *
 * ── 확정 단계에서 내용(매직 바이트)을 검사하지 않는다 ──────────────────────
 * 서명 PUT이 Content-Type(pdf·txt·md 셋 중 하나)과 Content-Length를 고정하고, 내려받기는 attachment이며, R2는
 * 앱과 다른 출처라 내용이 무엇이든 앱 출처에서 실행될 길이 없다. 악성 PDF는 매직 바이트로 걸러지지도 않는다.
 * 남는 것은 «미확정»이라는 상태 하나를 더 두는 비용뿐이다. 다시 볼 조건: 브라우저 안 미리보기(md 렌더 · pdf
 * 내장 뷰어)를 붙이거나 익명에게 열 때. 서버 multipart로 받지 않는 것도 같은 줄기다 — 규정 문서(RAG)는 파싱이
 * 필요해 서버가 바이트를 받지만 여기는 보관만 하면 되고, 서버가 버퍼링하면 동시 업로드에 메모리가 비례해 는다
 * (FilePresigner 주석 · #107).
 *
 * ── 상태로 잠근다 ────────────────────────────────────────────────────
 * 올리기·지우기는 DRAFT(폼이 접수 중)·CHANGES_REQUESTED에서만 된다. 판정은 FormResponseHistoryEntity.
 * requireAttachmentsEditable이 갖는다(엔티티가 전이·검증을 던진다). 목록·내려받기는 상태와 무관하다 — 작성자는
 * 반려된 것도 보고, 반려된 기획안의 첨부는 «무엇을 검토했는가»의 증거라 지우지 않는다.
 *
 * ── 이력이 아니라 감사 ───────────────────────────────────────────────
 * 첨부는 흐름을 바꾸지 않는 «추가»라 이력 테이블 대상이 아니다(ADR-0042). 지운 것만 감사 로그에 남기며
 * 원본 파일 이름은 싣지 않는다(ADR-0024 — 이름에 실명이 들어갈 수 있다).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProposalAttachmentServiceImpl implements ProposalAttachmentService {

    /** 첨부를 받는 시스템 폼. 늘릴 때는 이 한 줄과 «누가 보는가»의 규칙을 함께 정한다 */
    static final String ATTACHABLE_SYSTEM_FORM_CODE = ProposalFormSeed.SYSTEM_FORM_CODE;

    /** 받는 형식 — AttachmentFileType의 부분집합. 늘릴 때는 여기 한 줄이다(웹은 목록을 복제하지 않는다) */
    static final Set<AttachmentFileType> ALLOWED_TYPES =
            Set.of(AttachmentFileType.PDF, AttachmentFileType.TXT, AttachmentFileType.MD);

    /** 응답당 합계 상한. 파일 하나는 남은 용량까지 쓸 수 있다 */
    static final long MAX_TOTAL_SIZE_BYTES = 20L * 1024 * 1024;

    /** 응답당 개수 상한 */
    static final int MAX_COUNT = 5;

    private static final long BYTES_PER_MEGABYTE = 1024L * 1024;

    private static final String KEY_FORMAT =
            FileTargetType.FORM_RESPONSE.getObjectKeyPrefix() + "%d/%s";

    private final FormRepository formRepository;
    private final FormResponseHistoryRepository formResponseHistoryRepository;
    private final FileReferenceService fileReferenceService;
    private final FilePresigner filePresigner;

    /*
     * DRAFT 응답의 첨부를 열지 정하는 접수 판정. 자동 저장(saveDraft)이 쓰는 것과 같은 유일한 구현이다 —
     * 여기서 다시 계산하면 «답은 못 고치는데 첨부는 고칠 수 있는» 초안이 생긴다.
     */
    private final FormReceiptPolicy formReceiptPolicy;

    private final AuditLog auditLog;
    private final Clock clock;

    @Override
    @Transactional
    public ProposalAttachmentUploadResponse issueUploadUrl(
            Long formId,
            Long formResponseId,
            ProposalAttachmentUploadRequest request,
            MemberEntity author) {
        FormEntity form = findForm(formId);
        FormResponseHistoryEntity response = lockOwnResponse(form, formResponseId, author);
        requireAttachable(form);
        response.requireAttachmentsEditable(formReceiptPolicy.isAcceptingResponses(form));

        AttachmentFileType type = allowedTypeOf(request.fileName());

        // 응답 행을 잠근 뒤에 센다 — 같은 응답에 대한 다른 발급은 이 트랜잭션이 끝날 때까지 기다린다
        List<FileReferenceEntity> attached =
                fileReferenceService.findAllByTarget(FileTargetType.FORM_RESPONSE, formResponseId);
        if (attached.size() >= MAX_COUNT) {
            throw new GeneralException(
                    FormErrorCode.PROPOSAL_ATTACHMENT_LIMIT_EXCEEDED,
                    "첨부 자료는 %d개까지 올릴 수 있습니다.".formatted(MAX_COUNT));
        }
        long remaining = MAX_TOTAL_SIZE_BYTES - totalSizeOf(attached);
        if (request.fileSize() > remaining) {
            throw new GeneralException(
                    FormErrorCode.PROPOSAL_ATTACHMENT_TOO_LARGE,
                    "첨부 자료는 합계 %s까지 올릴 수 있습니다. 남은 용량은 %s입니다."
                            .formatted(
                                    megabytes(MAX_TOTAL_SIZE_BYTES),
                                    megabytes(Math.max(remaining, 0))));
        }

        String objectKey =
                KEY_FORMAT.formatted(formResponseId, UUID.randomUUID() + "." + type.getExtension());
        FileReferenceEntity reference =
                fileReferenceService.addAttachment(
                        FileTargetType.FORM_RESPONSE,
                        formResponseId,
                        objectKey,
                        request.fileName().trim(),
                        request.fileSize(),
                        author.getId(),
                        clock.instant());
        // 상한으로 남은 용량을 넘긴다 — 위 413은 안내이고, 강제는 이 거절과 서명의 Content-Length다(#638)
        String uploadUrl =
                filePresigner.presignPut(
                        objectKey, type.getContentType(), request.fileSize(), remaining);
        return new ProposalAttachmentUploadResponse(
                reference.getId(),
                uploadUrl,
                type.getContentType(),
                filePresigner.uploadUrlTtlSeconds());
    }

    @Override
    public ProposalAttachmentListResponse listMine(
            Long formId, Long formResponseId, MemberEntity author) {
        return listOf(findOwnResponse(findForm(formId), formResponseId, author).getId());
    }

    @Override
    public String downloadUrlOfMine(
            Long formId, Long formResponseId, Long fileId, MemberEntity author) {
        return downloadUrlOf(
                findOwnResponse(findForm(formId), formResponseId, author).getId(), fileId);
    }

    @Override
    @Transactional
    public void deleteMine(Long formId, Long formResponseId, Long fileId, MemberEntity author) {
        FormEntity form = findForm(formId);
        // 올리기와 같이 잠근다 — 지우는 사이에 제출이 끼어들어 «제출된 응답의 첨부가 바뀌는» 순서를 만들지 않는다
        FormResponseHistoryEntity response = lockOwnResponse(form, formResponseId, author);
        requireAttachable(form);
        response.requireAttachmentsEditable(formReceiptPolicy.isAcceptingResponses(form));

        FileReferenceEntity reference = findAttachment(formResponseId, fileId);
        // 감사 줄의 재료는 지우기 전에 읽어 둔다 — 행이 사라진 뒤에는 형식·크기를 물을 곳이 없다
        String decision = auditDecisionOf(formResponseId, reference);
        fileReferenceService.deleteOneOfTarget(
                FileTargetType.FORM_RESPONSE, formResponseId, fileId);
        auditLog.record(
                AuditEvent.success(AuditAction.FORM_RESPONSE_ATTACHMENT_DELETE)
                        .target(fileId)
                        .decision(decision)
                        .build());
    }

    @Override
    public ProposalAttachmentListResponse listForReview(Long formId, Long formResponseId) {
        requireResponseOfForm(findForm(formId), formResponseId);
        return listOf(formResponseId);
    }

    @Override
    public String downloadUrlForReview(Long formId, Long formResponseId, Long fileId) {
        requireResponseOfForm(findForm(formId), formResponseId);
        return downloadUrlOf(formResponseId, fileId);
    }

    @Override
    public ProposalAttachmentListResponse listOfResponse(Long formResponseId) {
        return listOf(formResponseId);
    }

    @Override
    public String downloadUrlOfResponse(Long formResponseId, Long fileId) {
        return downloadUrlOf(formResponseId, fileId);
    }

    @Override
    public long downloadUrlTtlSeconds() {
        return filePresigner.viewUrlTtlSeconds();
    }

    /*
     * 응답이 하드 삭제될 때의 정리 (server#685). file_rfrnc에 FK가 없어 DB가 대신 지워 주지 않는다 —
     * 응답을 지우는 쪽(초안 보존 정책)이 부른다. 대상 구분을 아는 자리를 이 클래스 하나로 두려고
     * FileReferenceService를 직접 부르게 하지 않았다.
     */
    @Override
    @Transactional
    public void deleteAllOfResponses(Collection<Long> formResponseIds) {
        fileReferenceService.deleteAllOfTargets(FileTargetType.FORM_RESPONSE, formResponseIds);
    }

    private ProposalAttachmentListResponse listOf(Long formResponseId) {
        List<FileReferenceEntity> references =
                fileReferenceService.findAllByTarget(FileTargetType.FORM_RESPONSE, formResponseId);
        return new ProposalAttachmentListResponse(
                references.stream().map(ProposalAttachmentResponse::of).toList(),
                totalSizeOf(references),
                MAX_TOTAL_SIZE_BYTES,
                MAX_COUNT);
    }

    /*
     * 파일 id만으로는 열지 않는다 — 대상(경로의 응답 id)까지 함께 걸어 찾는다(IDOR). 경로 A의 자격으로 B의
     * 파일을 받는 길을 막는 것이 이 한 줄이며, FilePresigner는 누구에게 내주는지 묻지 않으므로 이 순서가
     * 뒤집히면 안 된다.
     */
    private String downloadUrlOf(Long formResponseId, Long fileId) {
        FileReferenceEntity reference = findAttachment(formResponseId, fileId);
        return filePresigner.presignGet(reference.objectKey(), reference.getOriginalFileName());
    }

    private FileReferenceEntity findAttachment(Long formResponseId, Long fileId) {
        return fileReferenceService
                .findOneOfTarget(FileTargetType.FORM_RESPONSE, formResponseId, fileId)
                .orElseThrow(
                        () -> new GeneralException(FormErrorCode.PROPOSAL_ATTACHMENT_NOT_FOUND));
    }

    private FormEntity findForm(Long formId) {
        return formRepository
                .findByIdAndDeletedAtIsNull(formId)
                .orElseThrow(() -> new GeneralException(FormErrorCode.FORM_NOT_FOUND));
    }

    /* 남의 응답은 없는 응답과 같은 404 — 내 응답 상세(GET …/mine/{formRspnsId})와 같은 경계다 */
    private FormResponseHistoryEntity findOwnResponse(
            FormEntity form, Long formResponseId, MemberEntity author) {
        return formResponseHistoryRepository
                .findByIdAndFormAndMember(formResponseId, form, author)
                .orElseThrow(() -> new GeneralException(FormErrorCode.FORM_RESPONSE_NOT_FOUND));
    }

    private FormResponseHistoryEntity lockOwnResponse(
            FormEntity form, Long formResponseId, MemberEntity author) {
        return formResponseHistoryRepository
                .lockByIdAndFormAndMember(formResponseId, form, author)
                .orElseThrow(() -> new GeneralException(FormErrorCode.FORM_RESPONSE_NOT_FOUND));
    }

    /* 다른 폼의 응답 id는 없는 응답과 같은 404 — 운영자용 응답 상세(findByIdAndForm)와 같은 폼 경계다 */
    private void requireResponseOfForm(FormEntity form, Long formResponseId) {
        if (!formResponseHistoryRepository.existsByIdAndForm(formResponseId, form)) {
            throw new GeneralException(FormErrorCode.FORM_RESPONSE_NOT_FOUND);
        }
    }

    private static void requireAttachable(FormEntity form) {
        if (!ATTACHABLE_SYSTEM_FORM_CODE.equals(form.getSystemFormCode())) {
            throw new GeneralException(FormErrorCode.PROPOSAL_ATTACHMENT_NOT_SUPPORTED);
        }
    }

    /* 형식은 확장자로 찾고(AttachmentFileType.ofFileName) 그다음 허용 셋을 본다 — 표는 파일 도메인 한 벌이다 */
    private static AttachmentFileType allowedTypeOf(String fileName) {
        return AttachmentFileType.ofFileName(fileName)
                .filter(ALLOWED_TYPES::contains)
                .orElseThrow(
                        () ->
                                new GeneralException(
                                        FormErrorCode.PROPOSAL_ATTACHMENT_UNSUPPORTED_TYPE));
    }

    private static long totalSizeOf(List<FileReferenceEntity> references) {
        return references.stream()
                .map(FileReferenceEntity::getFileSize)
                .filter(Objects::nonNull)
                .mapToLong(Long::longValue)
                .sum();
    }

    /*
     * 감사 줄의 decision — 응답 id · 형식 · 크기. 형식은 원본 이름이 아니라 **오브젝트 키**의 확장자에서
     * 읽는다: 키는 서버가 uuid로 지은 것이라 사람의 글이 섞이지 않는다(ADR-0024).
     */
    private static String auditDecisionOf(Long formResponseId, FileReferenceEntity reference) {
        String type =
                AttachmentFileType.ofFileName(reference.objectKey())
                        .map(Enum::name)
                        .orElse("UNKNOWN");
        return "formRspnsId=%d type=%s size=%d"
                .formatted(formResponseId, type, reference.getFileSize());
    }

    /*
     * 바이트를 «7.7MB»처럼 읽히는 문장으로. 0.1MB 단위에서 **내림**한다 — 남은 용량을 부풀려 말하면
     * 작성자가 그 크기로 다시 골라도 413이 난다. 소수가 0이면 떼어 «20MB»로 쓴다.
     */
    static String megabytes(long bytes) {
        long tenths = bytes * 10 / BYTES_PER_MEGABYTE;
        long whole = tenths / 10;
        long fraction = tenths % 10;
        return fraction == 0 ? whole + "MB" : whole + "." + fraction + "MB";
    }
}
