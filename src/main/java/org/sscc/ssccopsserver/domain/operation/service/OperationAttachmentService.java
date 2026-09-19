package org.sscc.ssccopsserver.domain.operation.service;

import java.util.List;

import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.operation.dto.OperationAttachmentResponse;
import org.sscc.ssccopsserver.domain.operation.dto.OperationAttachmentUploadRequest;
import org.sscc.ssccopsserver.domain.operation.dto.OperationAttachmentUploadResponse;

/** 운영 건(업무·하위 업무·회의) 첨부 (#493 · ssccops#410) */
public interface OperationAttachmentService {

    OperationAttachmentUploadResponse issueUploadUrl(
            Long operationId, OperationAttachmentUploadRequest request, MemberEntity performer);

    List<OperationAttachmentResponse> list(Long operationId, MemberEntity performer);

    /** 내려받기 서명 URL — 조회 권한이면 된다 */
    String downloadUrlOf(Long operationId, Long fileId, MemberEntity performer);

    long downloadUrlTtlSeconds();

    void delete(Long operationId, Long fileId, MemberEntity performer);
}
