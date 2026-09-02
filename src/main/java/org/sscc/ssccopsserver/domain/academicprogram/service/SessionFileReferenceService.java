package org.sscc.ssccopsserver.domain.academicprogram.service;

import org.sscc.ssccopsserver.domain.academicprogram.dto.FileReferenceUploadRequest;
import org.sscc.ssccopsserver.domain.academicprogram.dto.FileReferenceUploadResponse;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

public interface SessionFileReferenceService {

    FileReferenceUploadResponse issueUploadUrl(
            Long academicProgramId,
            Long sessionId,
            FileReferenceUploadRequest request,
            MemberEntity requester);
}
