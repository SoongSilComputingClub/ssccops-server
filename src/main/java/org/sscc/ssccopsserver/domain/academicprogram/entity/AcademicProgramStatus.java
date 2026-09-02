package org.sscc.ssccopsserver.domain.academicprogram.entity;

/*
 * acdm_actv_stts_cd — 승인 이후 활동 진행 상태 (#131 → #133, 학술관리_데이터모델.md §3,
 * 2026-08-24 재설계). acdm_actv.acdm_actv_stts_cd에 문자열로 저장된다.
 *
 * `PROPOSED`/`REVISION_REQUESTED`/`REJECTED`는 없다 — 기획안 접수·검토·반려·수정요청은 전부
 * 폼 도메인(form_rspns_hstry, #141)의 상태이지 이 엔티티의 상태가 아니다. 반려된 기획안은
 * AcademicProgram 행 자체가 만들어지지 않으므로(폼 응답 단계에서 끝난다) 승인이 곧 생성이고,
 * 행은 항상 APPROVED로 태어난다(AcademicProgramEntity.create).
 *
 * 전이표(APPROVED → ONGOING → COMPLETED)와 그 검증은 AcademicProgramTransition·
 * AcademicProgramEntity.changeStatus가 갖는다(#133).
 */
public enum AcademicProgramStatus {
    APPROVED,
    ONGOING,
    COMPLETED;

    /*
     * 모집이 시작된 적이 있는가 (#138 · 신청자 조회·선발의 전제).
     *
     * "ONGOING 이후인가"이지 "지금 모집 중인가"가 아니다 — 끝난 활동(COMPLETED)의 신청 명부도
     * 지나간 사실이라 조회를 막을 이유가 없고, 지금 응답을 받을 수 있는지는 폼의 접수 판정
     * (FormReceiptPolicy)이 이미 답한다. 판단을 여기 두는 것은 SessionStatus.allowsRecording과
     * 같은 자리다 — 어떤 상태가 무엇을 허용하는지는 어휘를 가진 쪽이 안다.
     */
    public boolean hasStartedRecruitment() {
        return this != APPROVED;
    }
}
