package org.sscc.ssccopsserver.domain.academicprogram.service;

import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;
import org.sscc.ssccopsserver.domain.form.service.ProposalFormSeed;
import org.sscc.ssccopsserver.domain.form.service.SystemFormApprovalHook;

import lombok.RequiredArgsConstructor;

/*
 * 기획안(PROPOSAL) 폼 응답이 승인됐을 때 학술 도메인이 하는 일 (#150).
 *
 * ── 이 클래스가 두 도메인의 경계다 ────────────────────────────
 * 폼 도메인은 SystemFormApprovalHook 인터페이스만 알고, 그 구현이 학술 도메인에 있다는 것을
 * 모른다. 반대로 학술 도메인은 폼 도메인을 안다 — 기획안이 폼으로 접수된다는 것이 이 도메인의
 * 전제이기 때문이다. 의존은 한 방향(학술 → 폼)이며, 그 방향을 지키는 자리가 여기다.
 *
 * ── 얇게 유지한다 ─────────────────────────────────────────────
 * 판단을 여기 두지 않는다. 이관은 AcademicProgramMigrationService가, 파싱은
 * ProposalResponseParser가 갖는다 — 훅에 로직이 붙기 시작하면 "폼이 승인됐을 때"라는 진입
 * 조건에 묶인 코드가 생겨, 나중에 다른 경로(예: 운영자의 수동 개설)로 같은 일을 할 수 없다.
 */
@Component
@RequiredArgsConstructor
public class ProposalApprovalHook implements SystemFormApprovalHook {

    private final AcademicProgramMigrationService academicProgramMigrationService;
    private final ProposalResponseParser proposalResponseParser;

    @Override
    public String sysFormCd() {
        return ProposalFormSeed.SYSTEM_FORM_CODE;
    }

    @Override
    public void onAccepted(FormResponseHistoryEntity response) {
        academicProgramMigrationService.migrate(response);
    }

    /*
     * 검토 화면의 미리보기. 실제 이관과 **같은 파서**를 부르는 것이 이 메서드의 존재 이유다 —
     * 두 벌이면 검토자가 승인한 것과 만들어지는 것이 달라진다(ssccops#148 BR).
     */
    @Override
    public Object preview(FormResponseHistoryEntity response) {
        return proposalResponseParser.preview(response.getContent());
    }
}
