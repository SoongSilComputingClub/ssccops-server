package org.sscc.ssccopsserver.domain.academicprogram.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.academicprogram.code.error.AcademicProgramErrorCode;
import org.sscc.ssccopsserver.domain.academicprogram.repository.AcademicProgramRepository;
import org.sscc.ssccopsserver.domain.form.dto.ProposalAttachmentListResponse;
import org.sscc.ssccopsserver.domain.form.service.ProposalAttachmentService;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;

/*
 * 학술 활동 → 원 기획안 응답 → 그 응답의 첨부 (server#685 · ssccops#607).
 *
 * ── 복사가 아니라 원 응답을 읽는다 ────────────────────────────────────
 * 활동은 acdm_actv.form_rspns_id(NOT NULL · UNIQUE)로 자기가 태어난 응답을 들고 있고, 승인된 응답은
 * 바뀌지 않는다(ADR-0003 · 첨부도 ACCEPTED에서 잠긴다). 그래서 승인 때 첨부를 활동 대상으로 복사하지
 * 않았다 — 복사하면 승인 트랜잭션(ADR-0002)에 R2 복사라는 실패 지점이 생기고 오브젝트가 두 벌이 된다.
 * AcademicProgramEntity.formResponse 주석의 «이 필드를 읽어 활동의 값을 채우지 말 것»은 활동의 속성을
 * 응답에서 따라 읽지 말라는 뜻이고(이관은 복사다), 여기서 읽는 것은 활동의 값이 아니라 «무엇을 보고
 * 승인했는가»의 원본 자료다.
 *
 * ── 문턱은 활동 상세와 같다 ───────────────────────────────────────────
 * 가입한 회원 누구나(GET /v1/academic-programs/{id}와 같다) — 지원은 회원만 할 수 있으니 지원자는 전원
 * 이 범위에 든다. 회원 행 검사는 컨트롤러의 @CurrentMember가 끊고, 여기서는 활동이 있는지만 본다.
 * 그 뒤의 «이 파일이 그 응답의 것인가»(IDOR)는 폼 도메인이 대상까지 걸어 판정한다. 익명에게는 아무것도
 * 내주지 않는다 — 익명 층을 넓히는 것은 새 ADR이다(ADR-0038).
 *
 * 폼 서비스를 직접 부르는 것은 학술 → 폼 방향이 이미 있는 한 방향 결합이라서다(FormService·
 * ProposalApprovalHook) — 폼은 학술을 모르므로 순환이 생기지 않는다(DomainCycleTest).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AcademicProgramAttachmentServiceImpl implements AcademicProgramAttachmentService {

    private final AcademicProgramRepository academicProgramRepository;
    private final ProposalAttachmentService proposalAttachmentService;

    @Override
    public ProposalAttachmentListResponse list(Long academicProgramId) {
        return proposalAttachmentService.listOfResponse(proposalResponseIdOf(academicProgramId));
    }

    @Override
    public String downloadUrlOf(Long academicProgramId, Long fileId) {
        return proposalAttachmentService.downloadUrlOfResponse(
                proposalResponseIdOf(academicProgramId), fileId);
    }

    @Override
    public long downloadUrlTtlSeconds() {
        return proposalAttachmentService.downloadUrlTtlSeconds();
    }

    private Long proposalResponseIdOf(Long academicProgramId) {
        return academicProgramRepository
                .findById(academicProgramId)
                .map(program -> program.getFormResponse().getId())
                .orElseThrow(
                        () ->
                                new GeneralException(
                                        AcademicProgramErrorCode.ACADEMIC_PROGRAM_NOT_FOUND));
    }
}
