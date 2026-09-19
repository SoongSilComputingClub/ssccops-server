package org.sscc.ssccopsserver.domain.operation.service;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.file.code.AttachmentFileType;
import org.sscc.ssccopsserver.domain.file.code.FileTargetType;
import org.sscc.ssccopsserver.domain.file.entity.FileReferenceEntity;
import org.sscc.ssccopsserver.domain.file.service.FilePresigner;
import org.sscc.ssccopsserver.domain.file.service.FileReferenceService;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.repository.MemberRepository;
import org.sscc.ssccopsserver.domain.operation.code.error.OperationErrorCode;
import org.sscc.ssccopsserver.domain.operation.dto.MemberSummaryResponse;
import org.sscc.ssccopsserver.domain.operation.dto.OperationAttachmentResponse;
import org.sscc.ssccopsserver.domain.operation.dto.OperationAttachmentUploadRequest;
import org.sscc.ssccopsserver.domain.operation.dto.OperationAttachmentUploadResponse;
import org.sscc.ssccopsserver.domain.operation.entity.OperationEntity;
import org.sscc.ssccopsserver.domain.operation.repository.OperationRepository;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;
import org.sscc.ssccopsserver.global.audit.AuditAction;
import org.sscc.ssccopsserver.global.audit.AuditEvent;
import org.sscc.ssccopsserver.global.audit.AuditLog;

import lombok.RequiredArgsConstructor;

/*
 * 운영 건 첨부 (#493 · ssccops#410). 콘텐츠 갤러리(ContentPostImageServiceImpl)와 같은 흐름 —
 * 발급이 곧 참조 행이고(서버는 PUT을 관측하지 않는다), 실패한 PUT의 뒤처리는 웹이 DELETE로 한다.
 *
 * ── 대상은 oper 하나 ─────────────────────────────────────────
 * 업무·하위 업무·회의가 전부 oper의 확장이라 `FileTargetType.OPERATION` + operationId면 셋을 다 가리킨다.
 * 종류별로 갈리는 것은 권한뿐이고 그것은 OperationAttachmentAccessPolicy가 한다.
 *
 * ── 크기 상한 ─────────────────────────────────────────────
 * 이미지 10MB(FilePresigner)와 별개로 25MB — 발표 자료·압축이 흔하다. 실제 강제는 서명의 Content-Length.
 *
 * ── 이력이 아니라 감사 ────────────────────────────────────
 * 첨부는 흐름을 바꾸지 않는 «추가»라 이력 테이블 대상이 아니다(ADR-0042). 지운 것만 감사 로그에 남긴다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OperationAttachmentServiceImpl implements OperationAttachmentService {

    static final long MAX_ATTACHMENT_SIZE_BYTES = 25L * 1024 * 1024;
    private static final String KEY_FORMAT = "operations/%d/%s";

    private final OperationRepository operationRepository;
    private final MemberRepository memberRepository;
    private final FileReferenceService fileReferenceService;
    private final FilePresigner filePresigner;
    private final OperationAttachmentAccessPolicy accessPolicy;
    private final AuditLog auditLog;
    private final Clock clock;

    @Override
    @Transactional
    public OperationAttachmentUploadResponse issueUploadUrl(
            Long operationId, OperationAttachmentUploadRequest request, MemberEntity performer) {
        OperationEntity operation = operationOf(operationId);
        accessPolicy.requireWrite(operation, performer);

        AttachmentFileType type =
                AttachmentFileType.ofFileName(request.fileName())
                        .orElseThrow(
                                () ->
                                        new GeneralException(
                                                OperationErrorCode.UNSUPPORTED_ATTACHMENT_TYPE));
        if (request.fileSize() > MAX_ATTACHMENT_SIZE_BYTES) {
            throw new GeneralException(OperationErrorCode.ATTACHMENT_TOO_LARGE);
        }

        String objectKey =
                KEY_FORMAT.formatted(
                        operationId, UUID.randomUUID() + "." + type.primaryExtension());
        FileReferenceEntity reference =
                fileReferenceService.addAttachment(
                        FileTargetType.OPERATION,
                        operationId,
                        objectKey,
                        request.fileName().trim(),
                        request.fileSize(),
                        performer.getId(),
                        clock.instant());
        String uploadUrl =
                filePresigner.presignPut(objectKey, type.getContentType(), request.fileSize());
        return new OperationAttachmentUploadResponse(
                reference.getId(),
                uploadUrl,
                type.getContentType(),
                filePresigner.uploadUrlTtlSeconds());
    }

    @Override
    public List<OperationAttachmentResponse> list(Long operationId, MemberEntity performer) {
        OperationEntity operation = operationOf(operationId);
        accessPolicy.requireRead(operation, performer);

        List<FileReferenceEntity> references =
                fileReferenceService.findAllByTarget(FileTargetType.OPERATION, operationId);
        // 올린 사람 이름은 한 번에 — 첨부 수만큼 회원을 따로 읽지 않는다
        List<Long> uploaderIds =
                references.stream()
                        .map(FileReferenceEntity::getUploaderId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();
        Map<Long, MemberSummaryResponse> uploaders =
                memberRepository.findAllById(uploaderIds).stream()
                        .collect(
                                Collectors.toMap(
                                        MemberEntity::getId,
                                        MemberSummaryResponse::from,
                                        (a, b) -> a));
        return references.stream()
                .map(r -> OperationAttachmentResponse.of(r, uploaders.get(r.getUploaderId())))
                .toList();
    }

    @Override
    public String downloadUrlOf(Long operationId, Long fileId, MemberEntity performer) {
        OperationEntity operation = operationOf(operationId);
        accessPolicy.requireRead(operation, performer);
        FileReferenceEntity reference =
                fileReferenceService
                        .findOneOfTarget(FileTargetType.OPERATION, operationId, fileId)
                        .orElseThrow(
                                () ->
                                        new GeneralException(
                                                OperationErrorCode.ATTACHMENT_NOT_FOUND));
        return filePresigner.presignGet(reference.objectKey(), reference.getOriginalFileName());
    }

    @Override
    @Transactional
    public void delete(Long operationId, Long fileId, MemberEntity performer) {
        OperationEntity operation = operationOf(operationId);
        accessPolicy.requireWrite(operation, performer);
        if (!fileReferenceService.deleteOneOfTarget(
                FileTargetType.OPERATION, operationId, fileId)) {
            throw new GeneralException(OperationErrorCode.ATTACHMENT_NOT_FOUND);
        }
        auditLog.record(
                AuditEvent.success(AuditAction.OPERATION_ATTACHMENT_DELETE).target(fileId).build());
    }

    private OperationEntity operationOf(Long operationId) {
        return operationRepository
                .findByIdAndDeletedAtIsNull(operationId)
                .orElseThrow(() -> new GeneralException(OperationErrorCode.OPERATION_NOT_FOUND));
    }

    /** 테스트·문서용 — 상한을 한 곳에서 읽는다 */
    static Function<Long, Boolean> tooLarge() {
        return size -> size > MAX_ATTACHMENT_SIZE_BYTES;
    }
}
