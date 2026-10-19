package org.sscc.ssccopsserver.domain.form.service;

import java.util.Collection;

import org.sscc.ssccopsserver.domain.form.dto.ProposalAttachmentListResponse;
import org.sscc.ssccopsserver.domain.form.dto.ProposalAttachmentUploadRequest;
import org.sscc.ssccopsserver.domain.form.dto.ProposalAttachmentUploadResponse;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

/**
 * 기획안 응답의 첨부 자료 (server#685 · ssccops#607). 경로가 셋이고 자격이 셋이다.
 *
 * <ul>
 *   <li><b>작성자</b>({@code …/responses/mine/{formRspnsId}/attachments}) — 본인 응답만. 남의 응답은 내 응답 상세와 같은
 *       404다
 *   <li><b>검토자</b>({@code …/responses/{formRspnsId}/attachments}) — {@code RESPONSE_REVIEW}는 컨트롤러가
 *       끊는다. 여기서는 폼 범위만 본다
 *   <li><b>학술 활동</b> — 학술 도메인이 활동을 찾고 그 원 응답 id로 {@link #listOfResponse}·{@link
 *       #downloadUrlOfResponse}를 부른다
 * </ul>
 *
 * <p><b>«…OfResponse» 둘은 자격을 묻지 않는다.</b> {@code FilePresigner}와 같은 규칙이다 — 판정은 부르는 쪽이 이미 끝냈어야 하고, 그
 * 순서가 뒤집히면(서명해 두고 응답에서 거른다) 한 줄만 어긋나도 새어 나간다. 지금 부르는 쪽은 {@code GET /v1/academic-programs/{id}}와 같은
 * 문턱(가입한 회원)을 지난 학술 경로 하나다.
 */
public interface ProposalAttachmentService {

    /** 업로드 허가 발급 — 발급이 곧 첨부 한 건이다(확정 단계 없음) */
    ProposalAttachmentUploadResponse issueUploadUrl(
            Long formId,
            Long formResponseId,
            ProposalAttachmentUploadRequest request,
            MemberEntity author);

    ProposalAttachmentListResponse listMine(Long formId, Long formResponseId, MemberEntity author);

    String downloadUrlOfMine(Long formId, Long formResponseId, Long fileId, MemberEntity author);

    void deleteMine(Long formId, Long formResponseId, Long fileId, MemberEntity author);

    ProposalAttachmentListResponse listForReview(Long formId, Long formResponseId);

    String downloadUrlForReview(Long formId, Long formResponseId, Long fileId);

    /** 자격 판정 없음 — 부르는 쪽(학술 활동 경로)이 끝냈어야 한다 */
    ProposalAttachmentListResponse listOfResponse(Long formResponseId);

    /** 자격 판정 없음 — 부르는 쪽(학술 활동 경로)이 끝냈어야 한다 */
    String downloadUrlOfResponse(Long formResponseId, Long fileId);

    long downloadUrlTtlSeconds();

    /** 응답들이 하드 삭제될 때(초안 보존 정책) 그 첨부 행을 지우고 오브젝트는 커밋 뒤 지운다 */
    void deleteAllOfResponses(Collection<Long> formResponseIds);
}
