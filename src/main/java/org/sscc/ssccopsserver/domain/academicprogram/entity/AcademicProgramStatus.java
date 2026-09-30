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
 * 전이표(APPROVED → ONGOING ⇄ COMPLETED · APPROVED/ONGOING ⇄ DISCONTINUED)와 그 검증은
 * AcademicProgramTransition·AcademicProgramEntity가 갖는다(#133 · 재시작 #597 · 폐지 #611).
 *
 * DISCONTINUED(폐지 · #611 · ADR-0058)는 운영이 **중단된** 것이다 — 끝까지 한 COMPLETED와 한
 * 값에 섞지 않는다(학술국장이 가르려는 것이 그 둘이다). 종료처럼 쓰기를 멈추고, 학술국장이
 * 폐지 전 상태로 복원(REINSTATE)한다.
 *
 * ⚠️ 값을 더하면 acdm_actv_stts_cd CHECK와 acdm_actv_aprv.bfr_acdm_actv_stts_cd CHECK를 새
 * 마이그레이션으로 넓힌다(V27) — FlywayMigrationValidateTest.checkConstraintsMatchTheirEnums가
 * 대조한다. 아래 두 판정은 switch 식이라 값을 더하면 **컴파일이 먼저 묻는다** — 상태 넷이 된
 * 뒤로 «상태로 분기하는 자리가 하나씩 는다»(ADR-0058 «포기하는 것»)를 빠뜨리지 않게 하려는 것이다.
 */
public enum AcademicProgramStatus {
    APPROVED,
    ONGOING,
    COMPLETED,
    DISCONTINUED;

    /*
     * 모집이 시작된 적이 있는가 (#138 · 신청자 조회·선발의 전제).
     *
     * "ONGOING 이후인가"이지 "지금 모집 중인가"가 아니다 — 끝난 활동(COMPLETED)의 신청 명부도
     * 지나간 사실이라 조회를 막을 이유가 없고, 지금 응답을 받을 수 있는지는 폼의 접수 판정
     * (FormReceiptPolicy)이 이미 답한다. 판단을 여기 두는 것은 SessionStatus.allowsRecording과
     * 같은 자리다 — 어떤 상태가 무엇을 허용하는지는 어휘를 가진 쪽이 안다.
     *
     * **폐지(DISCONTINUED)는 참으로 읽는다**(#611). 폐지는 모집 전(APPROVED)에서도 진행 중에서도
     * 오므로 상태만으로는 «모집을 시작한 적이 있는가»에 답할 수 없다. 이 판정을 쓰는 자리는 신청
     * 명부의 조회와 선발인데, 선발(쓰기)은 AcademicProgramWritePolicy가 폐지를 먼저 409로 끊으므로
     * 남는 것은 조회뿐이다. 명부는 지나간 사실이라 COMPLETED처럼 열어 두고, 모집 전에 폐지된 건은
     * 폼이 DRAFT라 명부가 비어 있을 뿐이다. 여기서 막으면(RECRUITMENT_NOT_STARTED) 진행 중에
     * 폐지된 활동의 신청 명부가 «아직 모집 전»이라는 틀린 말과 함께 사라진다.
     */
    public boolean hasStartedRecruitment() {
        return switch (this) {
            case APPROVED -> false;
            case ONGOING, COMPLETED, DISCONTINUED -> true;
        };
    }

    /*
     * 이 활동에 지금 쓸 수 있는가 (#597 · ADR-0057 — 종료는 그 활동의 쓰기를 전부 멈춘다).
     *
     * 판단은 여기, 거절은 AcademicProgramWritePolicy 하나다. 화면이 폼을 여는 isEditable 두
     * 곳(계획 표 · 모집 폼)도 이 값을 곱하므로 버튼과 실제 판정이 갈리지 않는다.
     *
     * 기간(행사 종료일)이 아니라 상태로 판정한다 — 기간으로 막으면 마지막 회차 기록과 종료일
     * 뒤의 수정요청이 막힌다(ADR-0057 선택지 B). 조회는 이 값과 무관하다.
     *
     * 폐지도 종료와 같이 멈춘다(#611 · ADR-0058) — 거절 코드만 다르다(ACADEMIC_PROGRAM_DISCONTINUED ·
     * 화면이 «재시작»이 아니라 «복원»을 안내해야 한다).
     */
    public boolean acceptsWrites() {
        return switch (this) {
            case APPROVED, ONGOING -> true;
            case COMPLETED, DISCONTINUED -> false;
        };
    }
}
