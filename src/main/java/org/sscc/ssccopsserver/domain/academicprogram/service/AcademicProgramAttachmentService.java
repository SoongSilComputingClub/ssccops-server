package org.sscc.ssccopsserver.domain.academicprogram.service;

import org.sscc.ssccopsserver.domain.form.dto.ProposalAttachmentListResponse;

/** 학술 활동이 태어난 기획안의 첨부 자료를 읽는다 (server#685 · ssccops#607) — 승인 뒤 지원하는 회원이 내려받는다 */
public interface AcademicProgramAttachmentService {

    ProposalAttachmentListResponse list(Long academicProgramId);

    String downloadUrlOf(Long academicProgramId, Long fileId);

    long downloadUrlTtlSeconds();
}
